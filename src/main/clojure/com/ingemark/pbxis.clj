(ns com.ingemark.pbxis
  (require [com.ingemark.pbxis.service :as ps]
           [clojure.data.json :as json]
           [ring.util.response :as r]
           [net.cgrand.moustache :refer (app)]
           [ring.adapter.jetty :refer (run-jetty)]
           (ring.middleware [json-params :refer (wrap-json-params)]
                            [file :refer (wrap-file)])
           (com.ingemark.clojure [config :as cfg] [logger :refer :all]))
  (import org.asteriskjava.manager.ManagerConnectionFactory))

(defn ok [f & args] (fn [_] (let [ret (apply f args)]
                              ((if (nil? ret) r/not-found r/response) (json/json-str ret)))))

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
                                                      []))))]}]))

(defonce server (atom nil))

(defn ami-connect []
  (let [c (reset! ps/ami-connection
                  (-> (apply #(ManagerConnectionFactory. %1 %2 %3)
                             (mapv ((cfg/settings) :ami) [:ip-address :username :password]))
                      .createManagerConnection))]
    (doto c (.addEventListener ps/ami-listener) .login)))

(defn stop []
  (println "Shutting down")
  (when @server (doto @server .stop .join) (reset! server nil))
  (when @ps/ami-connection (.logoff @ps/ami-connection) (reset! ps/ami-connection nil))
  (when @ps/scheduler (.shutdown @ps/scheduler) (reset! ps/scheduler nil))
  nil)

(defn main []
  (stop)
  (cfg/initialize nil)
  (logdebug "Settings" (cfg/settings))
  (reset! ps/scheduler (java.util.concurrent.Executors/newSingleThreadScheduledExecutor))
  (ami-connect)
  (reset! server (run-jetty (-> (var app-main)
                                (wrap-file "static-content"))
                            (assoc (cfg/settings) :join? false))))