(ns com.ingemark.pbxis.service
  (require [clojure.set :as set]
           [clojure.core.incubator :refer (-?>)]
           [com.ingemark.clojure.logger :refer :all])
  (import [org.asteriskjava.manager.event
           ManagerEvent JoinEvent LeaveEvent DialEvent HangupEvent
           AgentCalledEvent AgentConnectEvent AgentCompleteEvent]
          (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn- empty-q [] (LinkedBlockingQueue.))

(def amiq-agnts (atom {}))

(def agnt-eventq (atom {}))

(defn- update-amiq-agnts [amiq-agnts agnt amiqs]
  (let [conj #(conj (or %1 #{}) %2)
        amiqs-for-add (into #{} amiqs)
        amiqs-for-remove (set/difference (into #{} (keys amiq-agnts)) amiqs-for-add)
        amiq-agnts (reduce #(update-in %1 [%2] conj agnt) amiq-agnts amiqs-for-add)]
    (reduce #(if (= (%1 %2) #{agnt}) (dissoc %1 %2) (update-in %1 [%2] disj agnt))
            amiq-agnts amiqs-for-remove)))

(defn- enq-event [agnt k & vs]
  (when-let [q (@agnt-eventq (re-find #"\d+$" agnt))] (.add q (vec (cons k vs)))))

(defn- broadcast-qcount [ami-event]
  (doseq [agnt (@amiq-agnts (ami-event :queue))] (enq-event agnt "queueCount" (ami-event :count))))

(defn config-agnt [agnt qs]
  (swap! agnt-eventq #(if (% agnt) % (assoc % agnt (empty-q))))
  (swap! amiq-agnts update-amiq-agnts agnt qs)
  {:agent agnt :queues qs})

(defn events-for [agnt]
  (when-let [q (@agnt-eventq agnt)]
    (let [suck #(.drainTo q %)
          evs (doto (java.util.ArrayList.) suck)]
      (if (seq evs)
        evs
        (when-let [head (.poll q 4 TimeUnit/SECONDS)]
          (Thread/sleep 50)
          (doto evs (.add head) suck))))))

(defn handle-ami-event [event]
  (let [unique-id (event :uniqueId)]
    (condp = (:class event)
      JoinEvent
      (broadcast-qcount event)
      LeaveEvent
      (broadcast-qcount event)
      DialEvent
      (when (= (event :subEvent) "Begin")
        (enq-event (event :callerIdNum) "outgoingCall"))
      HangupEvent
      (enq-event (event :callerIdNum) "outgoingCallEnded")
      AgentCalledEvent
      (enq-event (event :agentCalled) "incomingCall" unique-id (event :callerIdNum))
      AgentConnectEvent
      (enq-event (event :member) "incomingCallAccepted" unique-id)
      AgentCompleteEvent
      (enq-event (event :member) "incomingCallEnded"
                 unique-id
                 (event :talkTime)
                 (event :holdTime)
                 (-?> event :variables (.get "FILEPATH")))
      (logdebug "Ignoring event" (.getSimpleName (:class event))))))

(defn etest []
  #_((config-agnt "701" ["3000" "3001"])
  (config-agnt "702" ["3000"]))
  (handle-ami-event {:class JoinEvent :queue "3000" :count 1})
  (Thread/sleep 4)
  (handle-ami-event {:class AgentCalledEvent :agentCalled "SCCP/701"
                     :uniqueId "a" :callerIdNum "111111"})
  (Thread/sleep 4)
  (handle-ami-event {:class LeaveEvent :queue "3000" :count 0})
  (Thread/sleep 4)
  (handle-ami-event {:class AgentConnectEvent :member "SCCP/701" :uniqueId "a"})
  (Thread/sleep 4)
  (handle-ami-event {:class AgentCompleteEvent :member "SCCP/701" :uniqueId "a"
                     :talkTime 20 :holdTime 2}))