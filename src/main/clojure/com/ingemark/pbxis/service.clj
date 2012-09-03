(ns com.ingemark.pbxis.service
  (require [clojure.set :as set]
           [clojure.core.incubator :refer (-?>)]
           [com.ingemark.clojure.logger :refer :all])
  (import org.asteriskjava.manager.event.ManagerEvent
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
  (when-let [q (@agnt-eventq (re-find #"\d+$" (or agnt "")))] (.add q (vec (cons k vs)))))

(defn- broadcast-qcount [ami-event]
  (logdebug "broadcast-qcount" ami-event)
  (let [amiq (ami-event :queue)]
    (doseq [agnt (@amiq-agnts amiq)] (enq-event agnt "queueCount" amiq (ami-event :count)))))

(defn config-agnt [agnt qs]
  (swap! agnt-eventq #(if (% agnt) % (assoc % agnt (empty-q))))
  (swap! amiq-agnts update-amiq-agnts agnt qs)
  {:agent agnt :queues qs})

(defn events-for [agnt]
  (when-let [q (@agnt-eventq agnt)]
    (let [drain-events #(.drainTo q %)
          evs (doto (java.util.ArrayList.) drain-events)]
      (if (seq evs)
        evs
        (when-let [head (.poll q 4 TimeUnit/SECONDS)]
          (Thread/sleep 50)
          (doto evs (.add head) drain-events))))))

(defn handle-ami-event [event]
  (let [unique-id (event :uniqueId)]
    (condp = (:event-type event)
      "JoinEvent"
      (broadcast-qcount event)
      "LeaveEvent"
      (broadcast-qcount event)
      "DialEvent"
      (when (= (event :subEvent) "Begin")
        (enq-event ((spy "dial" event) :callerIdNum) "outgoingCall"))
      "HangupEvent"
      (enq-event ((spy "hangup" event) :callerIdNum) "outgoingCallEnded")
      "AgentCalledEvent"
      (enq-event ((spy "agentcalled" event) :agentCalled) "incomingCall" unique-id (event :callerIdNum))
      "AgentConnectEvent"
      (enq-event ((spy "agentconnect" event) :member) "incomingCallAccepted" unique-id)
      "AgentCompleteEvent"
      (enq-event ((spy "agentcomplete" event) :member) "incomingCallEnded"
                 unique-id
                 (event :talkTime)
                 (event :holdTime)
                 (-?> event :variables (.get "FILEPATH")))
      #_(logdebug "Ignoring event" (:event-type event)))))

(defn etest []
  #_((config-agnt "148" ["700" "3001"])
  (config-agnt "201" ["3000"]))
  (handle-ami-event {:class JoinEvent :queue "700" :count 1})
  (Thread/sleep 4)
  (handle-ami-event {:class AgentCalledEvent :agentCalled "SCCP/148"
                     :uniqueId "a" :callerIdNum "111111"})
  (Thread/sleep 4)
  (handle-ami-event {:class LeaveEvent :queue "700" :count 0})
  (Thread/sleep 4)
  (handle-ami-event {:class AgentConnectEvent :member "SCCP/148" :uniqueId "a"})
  (Thread/sleep 4)
  (handle-ami-event {:class AgentCompleteEvent :member "SCCP/148" :uniqueId "a"
                     :talkTime 20 :holdTime 2}))