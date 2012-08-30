(ns com.ingemark.pbxis
  (require [com.ingemark.pbxis.service :as svc]
           [clojure.string :as st]
           [clojure.data.json :as json]
           [ring.util.response :as r]
           [net.cgrand.moustache :refer (app delegate)]
           [ring.adapter.jetty :refer (run-jetty)]
           (ring.middleware [json-params :refer (wrap-json-params)]
                            [file :refer (wrap-file)])
           (com.ingemark.clojure [config :as cfg] [logger :refer :all]))
  (import (org.asteriskjava.manager ManagerConnectionFactory ManagerEventListener)))

(defn ok [f & args] (fn [_] (r/response (json/json-str (apply f args)))))

(def app-main
  (app
   wrap-json-params
   ["suggest"] {:get [{:keys [term shop]} (ok nil)]}
   ["ksrch"] {:get [{:keys [term shop cat-id order attach-cat start-at max-res]}
                    (ok nil)]}))

(defonce server (atom nil))

(defonce factory (atom nil))

(defonce connection (atom nil))

(defn connect []
  (reset! connection (.createManagerConnection factory))
  (.login connection)
  (.addEventListener connection (proxy [ManagerEventListener] []
                                  (onManagerEvent [event] (handle-ami-event event)))))

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
  (reset! server (run-jetty
                  (-> (var app-main)
                      (wrap-file "static-content"))
                  (assoc (cfg/settings) :join? false))))