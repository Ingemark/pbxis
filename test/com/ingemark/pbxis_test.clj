(ns com.ingemark.pbxis-test
  (:require [com.ingemark.pbxis :as pbxis]
            [com.ingemark.pbxis.specs]
            [com.ingemark.testutil :as testutil]
            [com.ingemark.logging :refer [loginfo logdebug]]
            [clojure.test :refer [deftest is are use-fixtures]]
            [clojure.test.check.generators :as gen]
            [clojure.spec.test.alpha :as stest]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]))

(use-fixtures :once testutil/with-instrumentation)

(def default-test-check-options {:clojure.spec.test.check/opts {:num-tests 30}})

;; This one throws exception in case of failures (causing a test error instead
;; of a failure) because I haven't figured out a better way for Cider to pretty
;; print the clojure.spec validation report.
(defn- check-spec
  ([sym]
   (check-spec sym nil))
  ([sym opts]
   (when-let [failures (->> (stest/check sym (merge default-test-check-options
                                                    opts))
                            (map :failure))]
     (if-not (every? nil? failures)
       (throw (ex-info (str "Generative test failed (see the cause for details), symbol " sym)
                       {:symbol sym}
                       (first (remove nil? failures))))
       (is true))))) ; this is here to avoid complaints about missing assertions

(deftest check-specs
  (let [syms [`pbxis/->qmember-summary-events]]
    (doseq [sym syms]
      (check-spec sym))))

(defn- ->queue-summary-events-args-generator []
  (gen/let [q (s/gen :com.ingemark.pbxis.specs/non-empty-string)]
    (gen/tuple
     (gen/fmap (fn [[qpe qme-vec]] (cons qpe qme-vec))
               (gen/tuple (gen/fmap #(assoc % :queue q)
                                    (s/gen :ami/queue-params-event))
                          (gen/vector (gen/fmap #(assoc % :queue q)
                                                (s/gen :ami/queue-member-event))
                                      3)))
     (gen/fmap #(vector (assoc % :queue q))
               (s/gen :ami/queue-summary-event)))))

(deftest qsummary-events-generative-test
  (check-spec `pbxis/->qsummary-events
              {:gen {:com.ingemark.pbxis.specs/->qsummary-events-args
                     ->queue-summary-events-args-generator}}))
