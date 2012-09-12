(ns com.ingemark.pbxis.service
  (require [clojure.set :as set] [clojure.data :as d]
           (clojure.core [incubator :refer (-?> -?>>)] [strint :refer (<<)])
           (com.ingemark.clojure [config :as cfg] [logger :refer :all]
                                 [utils :refer (upcase-first invoke)]))
  (import org.asteriskjava.manager.ManagerEventListener
          org.asteriskjava.manager.event.ManagerEvent
          (java.util.concurrent LinkedBlockingQueue TimeUnit)
          (clojure.lang Reflector RT)))

(defn- poll-timeout [] (-> (cfg/settings) :poll-timeout-seconds))
(defn- unsub-delay [] (quot (* (poll-timeout) 3) 2))
(defn- originate-timeout [] (-> (cfg/settings) :originate-timeout-seconds))
(def EVENT-BURST-MILLIS 100)
(def ACTIONID-TTL-SECONDS 10)

(defn- empty-q [] (LinkedBlockingQueue.))

(defonce ^:private lock (Object.))

(defonce scheduler (atom nil))

(defonce ami-connection (atom nil))

(defonce ^:private amiq-cnt-agnts (atom {}))

(defonce ^:private agnt-state (atom {}))

(defonce ^:private rndkey-agnt (atom {}))

(defonce ^:private agnt-unsubscriber (atom {}))

(defonce ^:private uniqueid-actionid (atom {}))

(defn- >?> [& fs] (reduce #(when %1 (%1 %2)) fs))

(defn- event-bean [event]
  (into (sorted-map)
        (-> (bean event)
            (dissoc :dateReceived :application :server :priority :appData :func :class
                    :source :timestamp :line :file :sequenceNumber :internalActionId)
            (assoc :event-type
              (let [c (-> event .getClass .getSimpleName)]
                (.substring c 0 (- (.length c) (.length "Event"))))))))

(defn- action [type params]
  (let [a (Reflector/invokeConstructor
           (RT/classForName (<< "org.asteriskjava.manager.action.~{type}Action"))
           (object-array 0))]
    (doseq [[k v] params] (invoke (<< "set~(upcase-first k)") a v))
    a))

(defn- send-action [a & [timeout]]
  (->
   (if timeout
     (.sendAction @ami-connection a timeout)
     (.sendAction @ami-connection a))
   spy
   .getResponse
   (= "Success")))

(defn- send-eventaction [a]
  (->>
   (doto (.sendEventGeneratingAction @ami-connection a) (-> .getResponse logdebug))
   .getEvents
   (mapv event-bean)))

(defn- schedule [task delay unit] (.schedule @scheduler task delay unit))

(defn- update-amiq-cnt-agnts [amiq-cnt-agnts agnt amiqs]
  (let [add #(if %1 (update-in %1 [:agnts] conj %2) {:agnts #{%2}})
        amiqs-for-add (into #{} amiqs)
        amiqs-for-remove (set/difference (into #{} (keys amiq-cnt-agnts)) amiqs-for-add)
        amiq-cnt-agnts (reduce #(update-in %1 [%2] add agnt) amiq-cnt-agnts amiqs-for-add)
        amiq-cnt-agnts (reduce #(if (= (>?> %1 %2 :agnts) #{agnt})
                                  (dissoc %1 %2) (update-in %1 [%2 :agnts] disj agnt))
                               amiq-cnt-agnts amiqs-for-remove)]
    (reduce (fn [amiq-cnt-agnts amiq]
              (update-in amiq-cnt-agnts [amiq :cnt]
                         #(or % (-?> (amiq-status amiq "$none$") first first :calls))))
            amiq-cnt-agnts (keys amiq-cnt-agnts))))

(declare ^:private config-agnt)

(defn- reschedule-agnt-unsubscriber [agnt]
  (let [newsched (schedule (fn [] (config-agnt agnt [])) (unsub-delay) TimeUnit/SECONDS)]
    (swap! agnt-unsubscriber update-in [agnt] #(do (-?> % (.cancel false)) newsched))))

(defn- rnd-key [] (-> (java.util.UUID/randomUUID) .toString))

(defn- agnt->location [agnt] (when agnt (str ((cfg/settings) :channel-prefix) agnt)))

(defn- amiq-status [amiq agnt]
  (reduce (fn [vect ev] (condp = (ev :event-type)
                          "QueueParams" (conj vect [ev])
                          "QueueMember" (conj (pop vect) (conj (peek vect) ev))
                          vect))
          []
          (send-eventaction (action "QueueStatus" {:member (agnt->location agnt) :queue amiq}))))

(def extension-status {0 "not_inuse" 1 "inuse" 2 "busy" 4 "unavailable" 8 "ringing"
                       16 "onhold"})

(defn- member-status [member]
  (let [p (:paused member), s (:status member)]
    (cond (nil? p) "loggedoff"
          (= 4 s) "invalid"
          (true? p) "paused"
          :else "loggedon"
          #_(condp = s
              0 "unknown"
              1 "not_inuse"
              2 "inuse"
              3 "busy"
              5 "unavailable"
              6 "ringing"
              7 "ringinuse"
              8 "onhold"))))

(defn- digits [s] (re-find #"\d+" (or s "")))

(defn- agnt-qs-status [agnt qs]
  (select-keys (into {} (for [[q-st member] (amiq-status nil agnt)]
                          [(:queue q-st) (member-status member)])) qs))

(defn- full-update-agent-amiq-status []
  (loginfo "Refreshing Asterisk queue status")
  (locking lock
    (let [now-agnt-state @agnt-state
          amiq-status (amiq-status nil nil)
          all-agnts-status (reduce (fn [m [q-st & members]]
                                     (reduce #(assoc-in %1 [(digits (:location %2)) (:queue q-st)]
                                                        (member-status %2))
                                             m members))
                                   {}
                                   amiq-status)
          all-qs-cnt (into {} (for [[q-st] amiq-status] [(:queue q-st) (:calls q-st)]))]
      (swap! amiq-cnt-agnts
         #(reduce (fn [amiq-cnt-agnts amiq]
                    (assoc-in amiq-cnt-agnts [amiq :cnt] (all-qs-cnt amiq))) % (keys %)))
      (swap! agnt-state
             (fn [agnt-state]
               (reduce (fn [agnt-state agnt]
                         (update-in agnt-state [agnt :amiq-status]
                                    #(merge (into {} (for [q (keys %)] [q "loggedoff"])))
                                    (all-agnts-status agnt)))
                       agnt-state
                       (keys agnt-state))))
      (into {} (for [[agnt state] now-agnt-state]
                 [agnt (second (d/diff (state :amiq-status) (all-agnts-status agnt)))])))))

(defn- update-one-agent-amiq-status [agnt q st]
  (locking lock
    (let [change? (not= st (>?> @agnt-state agnt :amiq-status q))]
      (when change? (swap! agnt-state assoc-in [agnt :amiq-status q] st))
      change?)))

(defn- replace-rndkey [agnt old new]
  (swap! rndkey-agnt #(-> % (dissoc old) (assoc new agnt))))

(defn- enq-event [agnt k & vs]
  (when-let [q (-?> (@agnt-state (digits agnt)) :eventq)]
    (.add q (spy "enq-event" agnt (vec (cons k vs))))))

(defn config-agnt [agnt qs]
  (logdebug "config-agnt" agnt qs)
  (locking lock
    (swap! amiq-cnt-agnts update-amiq-cnt-agnts agnt qs)
    (let [s (@agnt-state agnt)]
      (if (seq qs)
        (let [rndkey (rnd-key), eventq (empty-q)]
          (reschedule-agnt-unsubscriber agnt)
          (if s
            (do (replace-rndkey agnt (:rndkey s) rndkey)
                (swap! agnt-state update-in [agnt] assoc :rndkey rndkey :eventq eventq)
                (.add (s :eventq) ["requestInvalidated"]))
            (let [q-status (agnt-qs-status agnt qs)]
              (swap! rndkey-agnt assoc rndkey agnt)
              (swap! agnt-state assoc agnt {:rndkey rndkey :amiq-status q-status :eventq eventq})
              (enq-event agnt "queueMemberStatus" q-status)
              (enq-event agnt "queueCount"
                         (into {} (for [[amiq {:keys [cnt]}] (select-keys @amiq-cnt-agnts qs)]
                                    [amiq cnt])))))
          rndkey)
        (do
          (swap! rndkey-agnt dissoc (:rndkey s))
          (swap! agnt-state dissoc agnt)
          (<< "Agent ~{agnt} unsubscribed"))))))

(defn events-for [agnt-key]
  (when-let [[q rndkey] (locking lock
                          (when-let [agnt (@rndkey-agnt agnt-key)]
                            (let [rndkey (rnd-key)]
                              (replace-rndkey agnt agnt-key rndkey)
                              (reschedule-agnt-unsubscriber agnt)
                              [(>?> @agnt-state agnt :eventq) rndkey])))]
    (let [drain-events #(.drainTo q %)
          evs (doto (java.util.ArrayList.) drain-events)]
      (when-not (seq evs)
        (when-let [head (.poll q (poll-timeout) TimeUnit/SECONDS)]
          (Thread/sleep EVENT-BURST-MILLIS)
          (doto evs (.add head) drain-events)))
      {:key rndkey :events evs})))

(defn- broadcast-qcount [ami-event]
  (locking lock
    (let [amiq (ami-event :queue), cnt (ami-event :count)]
      (when-let [cnt-agnts (@amiq-cnt-agnts amiq)]
        (swap! amiq-cnt-agnts assoc-in [amiq :cnt] cnt)
        (doseq [agnt (cnt-agnts :agnts)] (enq-event agnt "queueCount" {amiq cnt}))))))

(def ^:private ignored-events
  #{"VarSet" "NewState" "NewChannel" "NewExten" "NewCallerId" "NewAccountCode" "ChannelUpdate"
    "RtcpSent" "RtcpReceived" "PeerStatus"})

(def ^:private activating-eventfilter (atom nil))

(defn- handle-ami-event [event]
  (let [t (:event-type event)]
    (if (ignored-events t)
      (when-not (-?>> event :privilege (re-find #"agent|call"))
        (let [tru (Object.)
              activating (swap! activating-eventfilter #(or % tru))]
          (when (= activating tru)
            (schedule #(try
                         (send-action
                          (spy (<< "Received unfiltered event ~{t}, privilege ~(event :privilege).")
                               "Sending" (action "Events" {:eventMask "agent,call"})))
                         (finally (reset! activating-eventfilter nil)))
                      0 TimeUnit/SECONDS))))
      (logdebug "AMI event\n" t (dissoc event :event-type :privilege)))
    (let [unique-id (event :uniqueId)]
      (condp = t
        "Connect"
        (schedule #(doseq [[agnt upd] (full-update-agent-amiq-status) :when upd]
                     (enq-event agnt "queueMemberStatus" upd))
                  0 TimeUnit/SECONDS)
        "Join"
        (broadcast-qcount event)
        "Leave"
        (broadcast-qcount event)
        "Dial"
        (when (= (event :subEvent) "Begin")
          (enq-event (event :channel) "dialOut" (event :srcUniqueId) (-> event :dialString digits))
          (let [dest-uniqueid (event :destUniqueId), agnt (event :destination)]
            (if-let [action-id (@uniqueid-actionid (event :srcUniqueId))]
              (do (enq-event agnt "callPlaced" action-id dest-uniqueid)
                  (swap! uniqueid-actionid dissoc dest-uniqueid))
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
            (do (swap! uniqueid-actionid assoc unique-id action-id)
                (schedule #(swap! uniqueid-actionid dissoc unique-id)
                          ACTIONID-TTL-SECONDS TimeUnit/SECONDS))
            (enq-event (event :exten) "placeCallFailed" action-id)))
        "ExtensionStatus"
        (enq-event (event :exten) "extensionStatus" (extension-status (event :status)))
        "QueueMemberStatus"
        (locking lock
          (let [q (event :queue), agnt (digits (event :location)), st (member-status event)]
            (when (update-one-agent-amiq-status agnt q st)
              (enq-event agnt "queueMemberStatus" {q st}))))
        "QueueMemberAdded"
        (let [q (event :queue), agnt (digits (event :location))
              st (member-status event)]
          (swap! agnt-state assoc-in [agnt :amiq-status q] st)
          (enq-event agnt "queueMemberStatus" {q st}))
        "QueueMemberRemoved"
        (let [q (event :queue), agnt (digits (event :location))]
          (swap! agnt-state assoc-in [agnt :amiq-status q] "loggedoff")
          (enq-event agnt "queueMemberStatus" {q "loggedoff"}))
        "QueueMemberPaused"
        (let [q (event :queue), agnt (digits (event :location))
              paused? (event :paused)]
          (schedule #(let [st (if paused? "paused" ((agnt-qs-status agnt [q]) q))]
                       (when (update-one-agent-amiq-status agnt q st)
                         (enq-event agnt "queueMemberStatus" {q st})))
                    0 TimeUnit/SECONDS))
        nil))))

(def ami-listener
  (reify ManagerEventListener
    (onManagerEvent [_ event] (handle-ami-event (event-bean event)))))

(defn- actionid [] (<< "pbxis-~(.substring (rnd-key) 0 8)"))

(defn originate-call [agnt phone]
  (let [actionid (actionid)
        context ((cfg/settings) :originate-context)]
    (when (send-action (action "Originate"
                               {:context context
                                :exten agnt
                                :channel (str "Local/" phone "@" context)
                                :actionId actionid
                                :priority (int 1)
                                :async true})
                       (originate-timeout))
      {:actionid actionid})))

(defn queue-action [type agnt params]
  (send-action (action (<< "Queue~(upcase-first type)")
                       (assoc params :interface (agnt->location agnt)))))
