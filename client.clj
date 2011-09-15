#!/usr/bin/env cljim
(ns client
  (:use (clojure (stacktrace :only (print-cause-trace)))
        (clojure.contrib (json :as json :only (read-json print-json))
                         (io :as io :only (reader)))))

(let [connection (-> (java.net.URL. (str "http://localhost:8082/eventstream")) (.openConnection))
      vmaticauth "dm1hdGljOnZtYXRpYw=="
      jgracinauth "amdyYWNpbjpqZ3JhY2lu"]
  (.setRequestProperty connection "Authorization" vmaticauth)
  (let [is (.getInputStream connection)]
   (loop []
     (let [msg (read-json (reader is))]
       (prn msg)
       (recur)))))

