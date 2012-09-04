(ns com.ingemark.pbxis.service
  (require [clojure.set :as set]
           (clojure.core [incubator :refer (-?>)] [strint :refer (<<)])
           [com.ingemark.clojure.logger :refer :all])
  (import org.asteriskjava.manager.event.ManagerEvent
          (java.util.concurrent ConcurrentHashMap LinkedBlockingQueue TimeUnit)))

(def POLL-TIMEOUT-SEC 15)
(def UNSUB-DELAY-SEC (+ POLL-TIMEOUT-SEC 2))
(def EVENT-BURST-MILLIS 50)

(defn- empty-q [] (LinkedBlockingQueue.))

(defonce lock (Object.))

(defonce scheduler (atom nil))

(defonce amiq-agnts (atom {}))

(defonce agnt-state (atom {}))

(defonce rndkey-agnt (atom {}))

(defonce agnt-unsubscriber (atom {}))

(defn- update-amiq-agnts [amiq-agnts agnt amiqs]
  (let [conj #(conj (or %1 #{}) %2)
        amiqs-for-add (into #{} amiqs)
        amiqs-for-remove (set/difference (into #{} (keys amiq-agnts)) amiqs-for-add)
        amiq-agnts (reduce #(update-in %1 [%2] conj agnt) amiq-agnts amiqs-for-add)]
    (reduce #(if (= (%1 %2) #{agnt}) (dissoc %1 %2) (update-in %1 [%2] disj agnt))
            amiq-agnts amiqs-for-remove)))

(declare config-agnt)

(defn- reschedule-agnt-unsubscriber [agnt]
  (let [newsched (.schedule @scheduler (fn [] (config-agnt agnt []))
                            UNSUB-DELAY-SEC TimeUnit/SECONDS)]
    (swap! agnt-unsubscriber update-in [agnt] #(do (-?> % (.cancel false)) newsched))))

(defn config-agnt [agnt qs]
  (logdebug "config-agnt" agnt qs)
  (locking lock
    (swap! amiq-agnts update-amiq-agnts agnt qs)
    (let [s (@agnt-state agnt)]
      (if (seq qs)
        (do (when-not s
              (let [rndkey (-> (java.util.UUID/randomUUID) .toString)]
                (swap! rndkey-agnt assoc rndkey agnt)
                (swap! agnt-state assoc agnt {:rndkey rndkey :eventq (empty-q)})))
            (reschedule-agnt-unsubscriber agnt)
            {:agent agnt :key ((@agnt-state agnt) :rndkey) :queues qs})
        (do
         (swap! rndkey-agnt dissoc (:rndkey s))
         (swap! agnt-state dissoc agnt)
         (<< "Agent ~{agnt} unsubscribed"))))))

(defn events-for [key]
  (locking lock
    (when-let [agnt (@rndkey-agnt key)]
      (let [q (-> (@agnt-state agnt) :eventq)]
        (reschedule-agnt-unsubscriber agnt)
        (let [drain-events #(.drainTo q %)
              evs (doto (java.util.ArrayList.) drain-events)]
          (when-not (seq evs)
            (when-let [head (.poll q POLL-TIMEOUT-SEC TimeUnit/SECONDS)]
              (Thread/sleep EVENT-BURST-MILLIS)
              (doto evs (.add head) drain-events)))
          evs)))))

(def ignored-events
  #{"VarSet" "NewState" "NewChannel" "NewExten" "NewCallerId" "NewAccountCode" "ChannelUpdate"
    "RtcpSent" "RtcpReceived" "QueueMemberStatus"})

(defn- broadcast-qcount [ami-event]
  (let [amiq (ami-event :queue)]
    (doseq [agnt (@amiq-agnts amiq)] (enq-event agnt "queueCount" amiq (ami-event :count)))))

(defn- digits [s] (re-find #"\d+" (or s "")))

(defn- enq-event [agnt k & vs]
  (when-let [q (-?> (@agnt-state (digits agnt)) :eventq)]
    (.add q (spy "enq-event" agnt (vec (cons k vs))))))

(defn handle-ami-event [event]
  (let [t (:event-type event)]
    (when-not (ignored-events t)
      (logdebug
       "AMI event\n" t
       (into (sorted-map)
             (dissoc event :event-type :dateReceived :privilege :application :server :context
                     :priority :appData :func :class :source :timestamp :line :file :sequenceNumber))))
    (let [unique-id (event :uniqueId)]
      (condp = t
        "Join"
        (broadcast-qcount event)
        "Leave"
        (broadcast-qcount event)
        "Dial"
        (when (= (event :subEvent) "Begin")
          (enq-event (event :channel) "dialOut" (event :srcUniqueId) (-> event :dialString digits))
          (enq-event (event :destination) "calledDirectly" (event :destUniqueId) (event :callerIdNum)))
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
        nil))))

(defn etest []
  #_((config-agnt "701" "148" ["700" "3001"])
  (config-agnt "201" ["3000"]))
  (handle-ami-event {:event-type "Join" :queue "700" :count 1})
  (Thread/sleep 4)
  (handle-ami-event {:event-type "AgentCalled" :agentCalled "SCCP/701"
                     :uniqueId "a" :callerIdNum "111111"})
  (Thread/sleep 4)
  (handle-ami-event {:event-type "Leave" :queue "700" :count 0})
  (Thread/sleep 4)
  (handle-ami-event {:evet-type "AgentConnect" :member "SCCP/701" :uniqueId "a"})
  (Thread/sleep 4)
  (handle-ami-event {:event-type "AgentComplete" :member "SCCP/701" :uniqueId "a"
                     :talkTime 20 :holdTime 2}))