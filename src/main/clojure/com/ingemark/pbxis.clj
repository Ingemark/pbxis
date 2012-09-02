(ns com.ingemark.pbxis
  (require [com.ingemark.pbxis.service :as ps]
           [clojure.string :as st]
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
  (reset! connection (.createManagerConnection factory))
  (.login connection)
  (.addEventListener connection
                     (reify ManagerEventListener
                       (onManagerEvent [_ event] (ps/handle-ami-event (bean event))))))

(defn initialize []
  (reset! factory (apply #(ManagerConnectionFactory. %1 %2 %3)
                         (mapv ((cfg/settings) :ami) [:ip-address :username :password]))))

(defn stop []
  (println "Shutting down")
  (when @server
    (doto @server .stop .join)
    (reset! server nil))
  nil)

(defn main []
  (cfg/initialize nil)
  (logdebug "Settings" (cfg/settings))
  (.addShutdownHook (Runtime/getRuntime) (Thread. stop))
  (reset! server (run-jetty (var app-main)
                            (assoc (cfg/settings) :join? false))))