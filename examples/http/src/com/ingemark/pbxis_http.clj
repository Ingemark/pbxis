(ns com.ingemark.pbxis-http
  (require (com.ingemark [pbxis :as px] [logging :refer :all])
           [clojure.data.json :as json]
           [ring.util.response :as r]
           [net.cgrand.moustache :refer (app)]
           [ring.adapter.jetty :refer (run-jetty)]
           (ring.middleware [json-params :refer (wrap-json-params)]
                            [file :refer (wrap-file)]
                            [file-info :refer (wrap-file-info)])))

(defn ok [f & args] (fn [_] (let [ret (apply f args)]
                              ((if (nil? ret) r/not-found r/response) (json/json-str ret)))))

(defn wrap-log-request [handle] #(->> % (spy "HTTP request") handle (spy "HTTP response")))

(def app-main
  (app
   wrap-json-params
   ["agent" id &]
   [[] {:post [{:strs [queues]} (ok px/config-agnt id queues)]
        :get (ok px/events-for id)}
    ["originate"] {:post [{:strs [phone]} (ok px/originate-call id phone)]}
    ["queue" action] {:post [{:as params}
                             (ok px/queue-action action id
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
  (px/ami-disconnect)
  nil)

(defn main []
  (stop)
  (let [host       "192.168.100.101"
        username   "admin"
        password   "protect"
        cfg {:channel-prefix "SCCP/"
             :originate-context "default"
             :originate-timeout-seconds 45
             :poll-timeout-seconds 30
             :unsub-delay-seconds 15
             :agent-gc-delay-minutes 180}]
    (px/ami-connect host username password cfg))
  (reset! server (run-jetty (-> (var app-main)
                                (wrap-file "static-content")
                                wrap-file-info)
                            {:port 58615, :join? false})))
