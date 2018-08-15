(ns com.ingemark.pbxis.specs
  (:require [clojure.spec.alpha :as s]
            [com.ingemark.pbxis :as pbxis]))

(s/def ::non-empty-string (s/and string? (comp not clojure.string/blank?)))

(s/def :ami/queue ::non-empty-string)
(s/def :ami/channel ::non-empty-string)
(s/def :ami/bridgeId ::non-empty-string)
(s/def :member/incall nat-int?)
(s/def :member/callsTaken nat-int?)
(s/def :member/lastCall nat-int?)
(s/def :member/paused boolean?)
(s/def :member/memberName ::non-empty-string)
(s/def :member/location ::non-empty-string)
(s/def :queue/available nat-int?)
(s/def :queue/holdTime nat-int?)
(s/def :queue/loggedIn nat-int?)
(s/def :queue/longestHoldTime nat-int?)
(s/def :queue/talkTime nat-int?)
(s/def :queue/abandoned nat-int?)
(s/def :queue/completed nat-int?)
(s/def :queue/serviceLevel nat-int?)
(s/def :queue/serviceLevelPerf double?)

(s/def :queue-params/event-type #{"QueueParams"})
(s/def :ami/queue-params-event
  (s/keys :req-un [:queue-params/event-type
                   :ami/queue
                   :queue/abandoned
                   :queue/completed
                   :queue/serviceLevel
                   :queue/serviceLevelPerf]))

(s/def :queue-member/event-type #{"QueueMember"})
(s/def :ami/queue-member-event
  (s/keys :req-un [:queue-member/event-type
                   :ami/queue
                   :member/incall
                   :member/callsTaken
                   :member/lastCall
                   :member/paused
                   :member/memberName ; TODO does this field exist in AMI?
                   :member/location]))

;; Returned by AMI QueueStatusAction
;;
;; From the JavaDoc of asterisk-java file QueueSummaryAction.java: "For each
;; queue a QueueParamsEvent is generated, followed by a QueueMemberEvent for
;; each member of that queue and a QueueEntryEvent for each entry in the queue."
;;
;; We're ignoring QueueEntryEvent.
(s/def :ami/queue-status-response-events
  (s/coll-of (s/or :queue-params-event :ami/queue-params-event
                   :queue-member-event :ami/queue-member-event)))

;; Returned by AMI QueueSummaryAction
(s/def :queue-summary/event-type #{"QueueSummary"})
(s/def :ami/queue-summary-event
  (s/keys :req-un [:queue-summary/event-type
                   :ami/queue
                   :queue/available
                   :queue/holdTime
                   :queue/loggedIn
                   :queue/longestHoldTime
                   :queue/talkTime]))


(s/def :member-summary-event/type #{"MemberSummary"})
(s/def ::qmember-summary-event
  (s/and (s/keys :req-un [:member-summary-event/type
                          :ami/queue]
                 :opt-un [:member/incall
                          :member/callsTaken
                          :member/lastCall
                          :member/paused
                          :member/memberName
                          :member/location])))

(s/def :queue-summary-event/type #{"QueueSummary"})
(s/def ::qsummary-event
  (s/and (s/keys :req-un [:queue-summary-event/type
                          :ami/queue
                          :queue/available
                          :queue/holdTime
                          :queue/loggedIn
                          :queue/longestHoldTime
                          :queue/talkTime
                          :queue/abandoned
                          :queue/completed
                          :queue/serviceLevel
                          :queue/serviceLevelPerf])))

(s/def :ami/status-event (s/keys :req-un [:ami/channel
                                          :ami/bridgeId]))

(s/def ::->qsummary-events-args (s/cat :ami-queue-status-events :ami/queue-status-response-events
                                       :ami-queue-summary-events (s/coll-of :ami/queue-summary-event)))

(s/fdef pbxis/->qsummary-events
  :args ::->qsummary-events-args
  :ret (s/coll-of ::qsummary-event))

(s/fdef pbxis/->qmember-summary-events
  :args (s/cat :ami-events (s/coll-of :ami/queue-member-event))
  :ret (s/coll-of ::qmember-summary-event)
  :fn (fn [{:keys [args ret]}]
        (if (some #(= (% :event-type) "QueueMember")
                  (:ami-events args))
          (<= 1 (count ret))
          (zero? (count ret)))))
