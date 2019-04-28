;; Copyright 2018 Ingemark d.o.o.
;;    Licensed under the Apache License, Version 2.0 (the "License");
;;    you may not use this file except in compliance with the License.
;;    You may obtain a copy of the License at
;;        http://www.apache.org/licenses/LICENSE-2.0
;;    Unless required by applicable law or agreed to in writing, software
;;    distributed under the License is distributed on an "AS IS" BASIS,
;;    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;;    See the License for the specific language governing permissions and
;;    limitations under the License.

(ns com.ingemark.pbxis.specs
  (:require [clojure.spec.alpha :as s]
            [com.ingemark.pbxis :as pbxis]))

(s/def ::non-empty-string (s/and string? (comp not clojure.string/blank?)))

(alias 'ami (create-ns 'com.ingemark.pbxis.ami))
(s/def ::ami/queue    ::non-empty-string)
(s/def ::ami/channel  ::non-empty-string)
(s/def ::ami/bridgeId ::non-empty-string)

(alias 'ami.member (create-ns 'com.ingemark.pbxis.ami.member))
(s/def ::ami.member/incall     nat-int?)
(s/def ::ami.member/callsTaken nat-int?)
(s/def ::ami.member/lastCall   nat-int?)
(s/def ::ami.member/paused     boolean?)
(s/def ::ami.member/memberName ::non-empty-string)
(s/def ::ami.member/location   ::non-empty-string)

(alias 'ami.queue (create-ns 'com.ingemark.pbxis.ami.queue))
(s/def ::ami.queue/available        nat-int?)
(s/def ::ami.queue/holdTime         nat-int?)
(s/def ::ami.queue/loggedIn         nat-int?)
(s/def ::ami.queue/longestHoldTime  nat-int?)
(s/def ::ami.queue/talkTime         nat-int?)
(s/def ::ami.queue/abandoned        nat-int?)
(s/def ::ami.queue/completed        nat-int?)
(s/def ::ami.queue/serviceLevel     nat-int?)
(s/def ::ami.queue/serviceLevelPerf double?)
(s/def ::ami.queue/calls            nat-int?)

(alias 'ami.event (create-ns 'com.ingemark.pbxis.ami.event))

(s/def :com.ingemark.pbxis.ami.event.queue-params-event/event-type #{"QueueParams"})
(s/def ::ami.event/queue-params-event
  (s/keys :req-un [:com.ingemark.pbxis.ami.event.queue-params-event/event-type
                   ::ami/queue
                   ::ami.queue/abandoned
                   ::ami.queue/completed
                   ::ami.queue/serviceLevel
                   ::ami.queue/serviceLevelPerf
                   ::ami.queue/calls]))

(s/def :com.ingemark.pbxis.ami.event.queue-member-event/event-type #{"QueueMember"})
(s/def ::ami.event/queue-member-event
  (s/keys :req-un [:com.ingemark.pbxis.ami.event.queue-member-event/event-type
                   ::ami/queue
                   ::ami.member/incall
                   ::ami.member/callsTaken
                   ::ami.member/lastCall
                   ::ami.member/paused
                   ::ami.member/memberName ; TODO does this field exist in AMI?
                   ::ami.member/location]))

;; Returned by AMI QueueStatusAction
;;
;; From the JavaDoc of asterisk-java file QueueSummaryAction.java: "For each
;; queue a QueueParamsEvent is generated, followed by a QueueMemberEvent for
;; each member of that queue and a QueueEntryEvent for each entry in the queue."
;;
;; We're ignoring QueueEntryEvent.
(s/def ::ami/queue-status-response-events
  (s/coll-of (s/or :queue-params-event ::ami.event/queue-params-event
                   :queue-member-event ::ami.event/queue-member-event)))

;; Returned by AMI QueueSummaryAction
(s/def :com.ingemark.pbxis.ami.event.queue-summary-event/event-type #{"QueueSummary"})
(s/def ::ami.event/queue-summary-event
  (s/keys :req-un [:com.ingemark.pbxis.ami.event.queue-summary-event/event-type
                   ::ami/queue
                   ::ami.queue/available
                   ::ami.queue/holdTime
                   ::ami.queue/loggedIn
                   ::ami.queue/longestHoldTime
                   ::ami.queue/talkTime]))

;;
;; The following are the events defined by PBXIS
;;
(s/def :com.ingemark.pbxis.event.qmember-summary-event/type #{"MemberSummary"})
(s/def ::pbxis/qmember-summary-event
  (s/keys :req-un [:com.ingemark.pbxis.event.qmember-summary-event/type
                   ::ami/queue]
          :opt-un [::ami.member/incall
                   ::ami.member/callsTaken
                   ::ami.member/lastCall
                   ::ami.member/paused
                   ::ami.member/memberName
                   ::ami.member/location]))

(s/def :com.ingemark.pbxis.event.queue-summary-event/type #{"QueueSummary"})
(s/def ::pbxis/queue-summary-event
  (s/keys :req-un [:com.ingemark.pbxis.event.queue-summary-event/type
                   ::ami/queue
                   ::ami.queue/available
                   ::ami.queue/holdTime
                   ::ami.queue/loggedIn
                   ::ami.queue/longestHoldTime
                   ::ami.queue/talkTime
                   ::ami.queue/abandoned
                   ::ami.queue/completed
                   ::ami.queue/serviceLevel
                   ::ami.queue/serviceLevelPerf
                   ::ami.queue/calls]))

(s/def ::->qsummary-events-args
  (s/cat :ami-queue-status-events ::ami/queue-status-response-events
         :ami-queue-summary-events (s/coll-of ::ami.event/queue-summary-event)))

(s/fdef pbxis/->qsummary-events
  :args ::->qsummary-events-args
  :ret (s/coll-of ::pbxis/queue-summary-event))

(s/fdef pbxis/->qmember-summary-events
  :args (s/cat :ami-events (s/coll-of ::ami.event/queue-member-event))
  :ret (s/coll-of ::pbxis/qmember-summary-event)
  :fn (s/and
       ;; must-generate-at-least-one-event-when-there-are-queue-members
       (fn [{:keys [args ret]}]
         (if (some #(= (% :event-type) "QueueMember")
                   (:ami-events args))
           (<= 1 (count ret))
           (zero? (count ret))))
       ;; must-not-generate-more-events-than-supplied-through-arguments
       (fn [{:keys [args ret]}]
         true)))
