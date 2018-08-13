(ns com.ingemark.testutil
  (:require  [clojure.test :as t :refer [is]]
             [clojure.spec.test.alpha :as stest]))

(defn with-instrumentation [f]
  (stest/instrument)
  (f))

