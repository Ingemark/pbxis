;; Copyright 2012 Inge-mark d.o.o.
;;    Licensed under the Apache License, Version 2.0 (the "License");
;;    you may not use this file except in compliance with the License.
;;    You may obtain a copy of the License at
;;        http://www.apache.org/licenses/LICENSE-2.0
;;    Unless required by applicable law or agreed to in writing, software
;;    distributed under the License is distributed on an "AS IS" BASIS,
;;    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;;    See the License for the specific language governing permissions and
;;    limitations under the License.

(ns com.ingemark.pbxis
  (require [com.ingemark.pbxis.service :as ps]
           [clojure.data.json :as json]
           [ring.util.response :as r]
           [net.cgrand.moustache :refer (app)]
           [ring.adapter.jetty :refer (run-jetty)]
           (ring.middleware [json-params :refer (wrap-json-params)]
                            [file :refer (wrap-file)]
                            [file-info :refer (wrap-file-info)])
           (com.ingemark.clojure [config :as cfg] [logger :refer :all])))

(defn ok [f & args] (fn [_] (let [ret (apply f args)]
                              ((if (nil? ret) r/not-found r/response) (json/json-str ret)))))

(defn wrap-log-request [handle] #(->> % (spy "HTTP request") handle (spy "HTTP response")))

(def app-main
  (app
   wrap-json-params
   ["agent" id &]
   [[] {:post [{:strs [queues]} (ok ps/config-agnt id queues)]
        :get (ok ps/events-for id)}
    ["originate"] {:post [{:strs [phone]} (ok ps/originate-call id phone)]}
    ["queue" action] {:post [{:as params}
                             (ok ps/queue-action action id
                                 (select-keys params
                                              (into ["queue"]
                                                    (condp = action
                                                      "add" ["memberName" "paused"]
                                                      "pause" ["paused"]
                                                      "remove" []))))]}]))

(defonce server (atom nil))

(defn stop []
  (println "Shutting down")
  (when @server (doto @server .stop .join) (reset! server nil))
  (ps/ami-disconnect)
  nil)

(defn main []
  (stop)
  (cfg/initialize nil)
  (logdebug "Settings" (cfg/settings))
  (apply ps/ami-connect (mapv ((cfg/settings) :ami) [:ip-address :username :password]))
  (reset! server (run-jetty (-> (var app-main)
                                (wrap-file "static-content")
                                wrap-file-info)
                            (assoc (cfg/settings) :join? false))))