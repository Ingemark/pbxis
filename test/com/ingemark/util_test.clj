(ns com.ingemark.util-test
  (:require [com.ingemark.pbxis.util :refer [call-event qcount-event name-event agnt-event member-event]]
            [clojure.test :as t]))

(t/deftest event-constructors
  (t/testing "construction of pbxis events"
    (t/is (= {:type "phoneNumber",
              :agent "1",
              :number "phoneNumber1",
              :unique-id "unique-id1"}
             (call-event "agnt1" "unique-id1" "phoneNumber1")))

    (t/is (= {:type "phoneNumber",
              :agent "1",
              :number "phoneNumber1",
              :unique-id "unique-id1"
              :queue "q1",
              :name "name1",
              :ami-event-unique-id "amiuid1"}
             (call-event "agnt1" "unique-id1" "phoneNumber1" :name "name1" :queue "q1" :ami-event-unique-id "amiuid1")))

    (t/is (= {:type "queueCount",
              :queue "q1",
              :count 10}
             (qcount-event "q1" 10)))

    (t/is (= {:type "agentName",
              :agent "1",
              :name "memberName1"}
             (name-event "agnt1" "memberName1")))

    (t/is (= {:type "callsInProgress",
              :agent "agnt1",
              :calls 10}
             (agnt-event "agnt1" "callsInProgress" :calls 10)))

    (t/is (= {:type "queueMemberStatus",
              :agent "agnt1",
              :queue "q1",
              :status "closed"}
             (member-event "agnt1" "q1" "closed")))))
