(ns com.ingemark.pbxis.service
  (require [clojure.set :as set]
           (clojure.core [incubator :refer (-?>)] [strint :refer (<<)])
           (com.ingemark.clojure [config :as cfg] [logger :refer :all]))
  (import org.asteriskjava.manager.event.ManagerEvent
          (org.asteriskjava.manager.action OriginateAction EventsAction)
          (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn- poll-timeout [] (-> (cfg/settings) :poll-timeout-seconds))
(defn- unsub-delay [] (+ (poll-timeout) 2))
(defn- event-burst [] 50)
(defn- originate-timeout [] (-> (cfg/settings) :originate-timeout-seconds))
(defn- actionid-ttl [] (+ (originate-timeout) 60))

(defn- empty-q [] (LinkedBlockingQueue.))

(defonce lock (Object.))

(defonce scheduler (atom nil))

(defonce ami-connection (atom nil))

(defonce amiq-agnts (atom {}))

(defonce agnt-state (atom {}))

(defonce rndkey-agnt (atom {}))

(defonce agnt-unsubscriber (atom {}))

(defonce originate-uniqueids (atom {}))

(defn- schedule [task delay unit] (.schedule @scheduler task delay unit))

(defn- update-amiq-agnts [amiq-agnts agnt amiqs]
  (let [conj #(conj (or %1 #{}) %2)
        amiqs-for-add (into #{} amiqs)
        amiqs-for-remove (set/difference (into #{} (keys amiq-agnts)) amiqs-for-add)
        amiq-agnts (reduce #(update-in %1 [%2] conj agnt) amiq-agnts amiqs-for-add)]
    (reduce #(if (= (%1 %2) #{agnt}) (dissoc %1 %2) (update-in %1 [%2] disj agnt))
            amiq-agnts amiqs-for-remove)))

(declare config-agnt)

(defn- reschedule-agnt-unsubscriber [agnt]
  (let [newsched (schedule (fn [] (config-agnt agnt [])) (unsub-delay) TimeUnit/SECONDS)]
    (swap! agnt-unsubscriber update-in [agnt] #(do (-?> % (.cancel false)) newsched))))

(defn- rnd-key [] (-> (java.util.UUID/randomUUID) .toString))

(defn config-agnt [agnt qs]
  (logdebug "config-agnt" agnt qs)
  (locking lock
    (swap! amiq-agnts update-amiq-agnts agnt qs)
    (let [s (@agnt-state agnt)]
      (if (seq qs)
        (do (when-not s
              (let [rndkey (rnd-key)]
                (swap! rndkey-agnt assoc rndkey agnt)
                (swap! agnt-state assoc agnt {:rndkey rndkey :eventq (empty-q)})))
            (reschedule-agnt-unsubscriber agnt)
            {:agent agnt :key ((@agnt-state agnt) :rndkey) :queues qs})
        (do
         (swap! rndkey-agnt dissoc (:rndkey s))
         (swap! agnt-state dissoc agnt)
         (<< "Agent ~{agnt} unsubscribed"))))))

(defn events-for [agnt-key]
  (locking lock
    (when-let [agnt (@rndkey-agnt agnt-key)]
      (let [q (-> (@agnt-state agnt) :eventq)]
        (reschedule-agnt-unsubscriber agnt)
        (let [drain-events #(.drainTo q %)
              evs (doto (java.util.ArrayList.) drain-events)]
          (when-not (seq evs)
            (when-let [head (.poll q (poll-timeout) TimeUnit/SECONDS)]
              (Thread/sleep (event-burst))
              (doto evs (.add head) drain-events)))
          evs)))))

(def ignored-events
  #{"VarSet" "NewState" "NewChannel" "NewExten" "NewCallerId" "NewAccountCode" "ChannelUpdate"
    "RtcpSent" "RtcpReceived" "QueueMemberStatus" "PeerStatus"})

(defn- digits [s] (re-find #"\d+" (or s "")))

(defn- enq-event [agnt k & vs]
  (when-let [q (-?> (@agnt-state (digits agnt)) :eventq)]
    (.add q (spy "enq-event" agnt (vec (cons k vs))))))

(defn- broadcast-qcount [ami-event]
  (let [amiq (ami-event :queue)]
    (doseq [agnt (@amiq-agnts amiq)] (enq-event agnt "queueCount" amiq (ami-event :count)))))

(defonce uniqueid<->actionid (atom {}))

(defn handle-ami-event [event]
  (let [t (:event-type event)]
    (when-not (ignored-events t)
      (logdebug
       "AMI event\n" t
       (into (sorted-map)
             (dissoc event :event-type :dateReceived :application :server :context
                     :priority :appData :func :class :source :timestamp :line :file :sequenceNumber))))
    (when (= -1 (-?> event :privilege (.indexOf "call")))
      (schedule #(logdebug "event mask" (.sendAction @ami-connection (EventsAction. "call")))
                0 TimeUnit/SECONDS))
    (let [unique-id (event :uniqueId)]
      (condp = t
        "Join"
        (broadcast-qcount event)
        "Leave"
        (broadcast-qcount event)
        "Dial"
        (when (= (event :subEvent) "Begin")
          (enq-event (event :channel) "dialOut" (event :srcUniqueId) (-> event :dialString digits))
          (let [dest-uniqueid (event :destUniqueId)
                agnt (event :destination)]
            (if-let [action-id (@uniqueid<->actionid (event :srcUniqueId))]
              (do (enq-event agnt "callPlaced" action-id dest-uniqueid)
                  (swap! uniqueid<->actionid dissoc dest-uniqueid))
              (enq-event agnt "calledDirectly" dest-uniqueid (event :callerIdNum)))))
        "Hangup"
        (enq-event (event :channel) "hangup" unique-id)
        "AgentCalled"
        (enq-event (event :agentCalled) "agentCalled" unique-id (event :callerIdNum))
        "AgentConnect"
        (enq-event (event :member) "agentConnect" unique-id)
        "AgentComplete"
        (enq-event (event :member) "agentComplete"
                   unique-id (event :talkTime) (event :holdTime)
                   (-?> event :variables (.get "FILEPATH")))
        "OriginateResponse"
        (let [action-id (event :actionId)]
          (if (= (event :response) "Success")
            (let [newstate (swap! uniqueid<->actionid
                                  #(if (% action-id)
                                     (-> % (dissoc action-id) (assoc unique-id action-id))
                                     %))]
              (when (newstate unique-id)
                (schedule #(swap! uniqueid<->actionid dissoc unique-id)
                          (actionid-ttl) TimeUnit/SECONDS)))
            (enq-event (event :exten) "placeCallFailed" action-id)))
        nil))))

(defn originate-call [agnt-key phone]
  (when-let [agnt (@rndkey-agnt agnt-key)]
    (let [actionid (<< "pbxis-~(.substring (rnd-key) 0 8)")]
      (swap! uniqueid<->actionid assoc actionid "")
      (schedule #(swap! uniqueid<->actionid dissoc actionid)
                (actionid-ttl) TimeUnit/SECONDS)
      (when (-> @ami-connection
                (.sendAction (doto (OriginateAction.)
                               (.setContext ((cfg/settings) :originate-context))
                               (.setExten agnt)
                               (.setChannel phone)
                               (.setActionId actionid)
                               (.setPriority (int 1))
                               (.setAsync true))
                             (originate-timeout))
                spy
                .getResponse
                (= "Success"))
        {:actionid actionid}))))

(defn etest []
  #_((config-agnt "148" ["700" "3001"])
  (config-agnt "201" ["3000"]))
  (handle-ami-event {:event-type "Join" :queue "700" :count 1})
  (Thread/sleep 4)
  (handle-ami-event {:event-type "AgentCalled" :agentCalled "SCCP/148"
                     :uniqueId "a" :callerIdNum "111111"})
  (Thread/sleep 4)
  (handle-ami-event {:event-type "Leave" :queue "700" :count 0})
  (Thread/sleep 4)
  (handle-ami-event {:evet-type "AgentConnect" :member "SCCP/148" :uniqueId "a"})
  (Thread/sleep 4)
  (handle-ami-event {:event-type "AgentComplete" :member "SCCP/148" :uniqueId "a"
                     :talkTime 20 :holdTime 2}))