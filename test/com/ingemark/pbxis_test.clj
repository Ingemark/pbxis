(ns com.ingemark.pbxis-test
  (:require [com.ingemark.pbxis :as pbxis]
            [com.ingemark.pbxis.specs :as pbxis.specs]
            [com.ingemark.testutil :as testutil]
            [com.ingemark.logging :refer [loginfo logdebug]]
            [clojure.test :refer [deftest is are use-fixtures]]
            [clojure.test.check.generators :as gen]
            clojure.spec.test.alpha
            [clojure.spec.alpha :as s]))

(use-fixtures :once testutil/with-instrumentation)

(alias 'ami.event (create-ns 'com.ingemark.pbxis.ami.event))

;; This one throws exception in case of failures (causing a test error instead
;; of a failure) because I haven't figured out a better way for Cider to pretty
;; print the clojure.spec validation report.
(defn- check-spec
  ([sym]
   (check-spec sym {}))
  ([sym opts]
   (let [failures (->> (clojure.spec.test.alpha/check sym opts)
                       (map :failure)
                       (remove nil?))]
     (if (seq failures)
       (throw (ex-info (str "Generative test failed (see the cause for details), symbol " sym)
                       {:symbol sym}
                       (first failures)))
       (is true))))) ; this is here to avoid complaints about missing assertions

(deftest check-specs
  (let [syms [`pbxis/->qmember-summary-events]]
    (doseq [sym syms]
      (check-spec sym))))

;; This is far from perfect. E.g. all generated events are for the same queue.
(defn- ->queue-summary-events-args-generator []
  (gen/let [q (s/gen ::pbxis.specs/non-empty-string)]
    (gen/tuple
     (gen/fmap (fn [[qpe qme-vec]] (cons qpe qme-vec))
               (gen/tuple (gen/fmap #(assoc % :queue q)
                                    (s/gen ::ami.event/queue-params-event))
                          (gen/vector (gen/fmap #(assoc % :queue q)
                                                (s/gen ::ami.event/queue-member-event))
                                      3)))
     (gen/fmap #(vector (assoc % :queue q))
               (s/gen ::ami.event/queue-summary-event)))))

(deftest qsummary-events-generative-test
  (check-spec `pbxis/->qsummary-events
              {:gen {::pbxis.specs/->qsummary-events-args
                     ->queue-summary-events-args-generator}}))
