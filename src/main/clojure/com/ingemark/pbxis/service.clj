(ns com.ingemark.pbxis.service
  (require [clojure.set :as set]
           [com.ingemark.clojure.logger :refer :all])
  (import [org.asteriskjava.manager.event
           ManagerEvent JoinEvent LeaveEvent DialEvent HangupEvent
           AgentCalledEvent AgentConnectEvent AgentCompleteEvent]))

(def empty-q clojure.lang.PersistentQueue/EMPTY)

(def q-agnts (atom {"3000" #{"701"}
                    "3001" #{"701" "702"}}))

(defonce agnt-eventq (atom {}))

(defn- update-qagnts [q-agnts agnt qs]
  (let [conj #(conj (or %1 #{}) %2)
        qs-for-add (into #{} qs)
        qs-for-remove (set/difference (into #{} (keys q-agnts)) qs-for-add)
        q-agnts (reduce #(update-in %1 [%2] conj agnt) q-agnts qs-for-add)]
    (reduce #(if (= (%1 %2) #{agnt}) (dissoc %1 %2) (update-in %1 [%2] disj agnt))
            q-agnts qs-for-remove)))

(defn config-agnt [agnt & qs]
  (swap! agnt-eventq (fn [agnt-eventq] (update-in agnt-eventq [agnt] #(or % empty-q))))
  (swap! q-agnts update-qagnts agnt qs))

(defn- enq-event [agnt k & vs]
  (when-let [q (agnt-eventq (re-find #"\d+$" agnt))] (swap! q conj (vec (cons k vs)))))

(defn- broadcast-qcount [q ami-event]
  (doseq [agnt (@q-agnts (ami-event :queue))] (enq-event agnt "queueCount" (ami-event :count))))

(defn handle-ami-event [^ManagerEvent event-object]
  (let [event (bean event-object)
        unique-id (event :uniqueId)]
    (condp instance? event-object
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
                 (.get (event :variables) "FILEPATH"))
      (logdebug "Ignoring event" (.getSimpleName (type event-object))))))
