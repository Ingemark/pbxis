(ns com.ingemark.pbxis
  (require [com.ingemark.pbxis.service :as ps]
           [clojure.data.json :as json]
           [ring.util.response :as r]
           [net.cgrand.moustache :refer (app)]
           [ring.adapter.jetty :refer (run-jetty)]
           (ring.middleware [json-params :refer (wrap-json-params)]
                            [file :refer (wrap-file)])
           (com.ingemark.clojure [config :as cfg] [logger :refer :all]))
  (import (org.asteriskjava.manager ManagerConnectionFactory ManagerEventListener)))

(defn ok [f & args] (fn [_] (r/response (json/json-str (apply f args)))))

(def app-main
  (app
   wrap-json-params
   ["agent" id] {:post [{:strs [queues]} (ok ps/config-agnt id queues)]
                 :get (ok ps/events-for id)}))

(defonce server (atom nil))

(defn ami-connect []
  (reset!
   ps/ami-connection
   (doto (-> (apply #(ManagerConnectionFactory. %1 %2 %3)
                    (mapv ((cfg/settings) :ami) [:ip-address :username :password]))
             .createManagerConnection)
     (.addEventListener
      (reify ManagerEventListener
        (onManagerEvent [_ event]
          (ps/handle-ami-event
           (assoc (bean event) :event-type
                  (let [c (-> event .getClass .getSimpleName)]
                    (.substring c 0 (- (.length c) (.length "Event")))))))))
     .login)))

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
  (ami-connect)
  (reset! ps/scheduler (java.util.concurrent.Executors/newSingleThreadScheduledExecutor))
  (reset! server (run-jetty (var app-main)
                            (assoc (cfg/settings) :join? false))))