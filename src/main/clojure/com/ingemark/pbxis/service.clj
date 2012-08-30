(ns com.ingemark.pbxis.service
  (import [org.asteriskjava.manager.event
           ManagerEvent JoinEvent LeaveEvent BridgeEvent HangupEvent QueueMemberStatusEvent
           AgentCalledEvent AgentConnectEvent AgentCompleteEvent]))

(def empty-q clojure.lang.PersistentQueue/EMPTY)

(defonce agnt-event-qs (atom {}))

(defonce agnt-props (atom {}))

(defonce caller-q (atom empty-q))

(defonce call-props (atom {}))

(defn- update-agnt-state [agnt status unique-id & channel]
  (let [newstate {:status status :unique-id unique-id}
        newstate (if (empty? channel) newstate (assoc newstate :channel (first channel)))]
    (if-let [state (agnt-props agnt)]
      (apply swap! state assoc (seq newstate))
      (swap! agnt-props assoc agnt (atom newstate)))))

(defn- enq-event [agnt k & vs]
  (let [ev (vec (cons k vs))]
    (if-let [q (agnt-event-qs agnt)]
      (swap! q conj ev)
      (swap! agnt-event-qs assoc agnt (atom (conj empty-q ev))))))

(defn handle-ami-event [^ManagerEvent event-object]
  (let [event (bean event-object)
        id (event :uniqueId)
        cnt (event :count)
        agnt (or (event :agentCalled) (event :member))]
    (condp instance? event-object
      LeaveEvent
      (enq-event agnt "queueChanged" cnt)
      AgentCalledEvent
      (do (update-agnt-state "RINGING" id (event :destinationChannel))
          (enq-event agnt "ringing" (:caller-id (get @call-props id))))
      JoinEvent
      (do (swap! call-props assoc id {:caller-id (event :callerIdNum), :queue (event :queue)})
          (enq-event agnt "queueChanged" cnt))
      AgentConnectEvent
      (do (update-agnt-state "BUSY" id)
          (enq-event agnt "callAccepted" (:caller-id (get @call-props id))))
      AgentCompleteEvent
      (do (update-agnt-state "FREE" nil nil)
          (enq-event agnt "agentCallOver"
                     (:caller-id (@call-props id))
                     (event :talkTime)
                     (event :holdTime)
                     (.get (event :variables) "FILEPATH"))
          (enq-event agnt "agentAvailable")
          (swap! call-props dissoc id))
      HangupEvent
      (let [agnt-props (@agnt-props agnt)]
        (when (or (= id (:unique-id agnt-props))
                  (= (event :channel) (agnt-props :channel)))
          (update-agnt-state agnt "FREE" nil nil)
          (swap! call-props dissoc id)
          (enq-event agnt "agentAvailable")))
      (println (str "Event will not be processed" (class event))))))
