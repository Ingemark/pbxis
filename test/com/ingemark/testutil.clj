(ns com.ingemark.testutil
  (:require  [clojure.test :as t :refer [is]]
             [orchestra.spec.test :as stest]))

(defn with-instrumentation [f]
  (stest/instrument)
  (f))

