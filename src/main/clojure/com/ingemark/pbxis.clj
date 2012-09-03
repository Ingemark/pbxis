(ns com.ingemark.pbxis
  (require [com.ingemark.pbxis.service :as ps]
           [clojure.string :as s]
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

(defonce factory (atom nil))

(defonce connection (atom nil))

(defn connect []
  (reset! connection (.createManagerConnection @factory))
  (.login @connection)
  (.addEventListener @connection
                     (reify ManagerEventListener
                       (onManagerEvent [_ event]
                         (ps/handle-ami-event
                          (assoc (bean event) :event-type (.getSimpleName (.getClass event))))))))

(defn initialize []
  (reset! factory (apply #(ManagerConnectionFactory. %1 %2 %3)
                         (mapv ((cfg/settings) :ami) [:ip-address :username :password]))))

(defn stop []
  (println "Shutting down")
  (when @server
    (doto @server .stop .join)
    (reset! server nil))
  (when @connection
    (.logoff @connection)
    (reset! connection nil))
  (reset! factory nil)
  nil)

(defn main []
  (stop)
  (cfg/initialize nil)
  (logdebug "Settings" (cfg/settings))
  (.addShutdownHook (Runtime/getRuntime) (Thread. stop))
  (initialize)
  (connect)
  (reset! server (run-jetty (var app-main)
                            (assoc (cfg/settings) :join? false))))