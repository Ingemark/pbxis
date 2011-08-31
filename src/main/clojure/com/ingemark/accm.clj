(ns com.ingemark.accm
  (:use com.ingemark.clojure.logger
        (compojure core [route :as route])
        [com.ingemark.ring-jetty-adapter :only (run-jetty)]
        (ring.util [servlet :as servlet])
        [ring.middleware file file-info [params :only (wrap-params)]]
        [clojure.contrib.json :only (json-str read-json)])
  (:import (org.mortbay.jetty Server Request Response)
           (org.mortbay.jetty.handler AbstractHandler)
           (org.asteriskjava.manager ManagerConnectionFactory ManagerConnection
                                     ManagerEventListener)
           (org.asteriskjava.manager.event ManagerEvent)))

(defrecord AppState [^ManagerConnectionFactory manager-connection-factory
                     ^ManagerConnection manager-connection])

(defonce appstate (atom (AppState. nil nil)))

(defn m [cnt] (str "{\"id\":\"" cnt "\", \"type\":\"update\"}"))

(def queues (atom []))

(defn- printout [w]
  (doseq [cnt (range 30)]
    (.write w (.getBytes "A"))
    (.flush w)
    (Thread/sleep 15000)))

(defroutes main-routes
  (GET "/events" request
       (let [myq (java.util.concurrent.LinkedBlockingQueue.)]
         (swap! queues conj myq)
         (when-let [response (:response request)]
           (when-let [w (.getOutputStream response)]
             (loop []
               (let [msg (.take myq)]
                 (.write w (.getBytes msg))
                 (.flush w))
               (recur))))
         {:status 200 :body (java.io.ByteArrayInputStream. (.getBytes ""))}))
  (POST "/call" request
        {:status 200 :body "Call originated."}))

(defonce server (atom nil))

(defn- handle-ami-event
  "Called on each AMI event that we receive from asterisk-java framework."
  [^ManagerEvent event]
  (logdebug "received AMI event")
  (doseq [q @queues]
    (.offer q (str (class event)))))

(defn main []
  (if @server
    "Server already running"
    (do
      (let [factory (ManagerConnectionFactory. "192.168.18.30" "manager" "oracle")
            connection (.createManagerConnection factory)]
        (swap! appstate
               assoc :manager-connection-factory factory :manager-connection connection)
        (.login connection)
        (.addEventListener connection (proxy [ManagerEventListener] []
                                        (onManagerEvent [event] (handle-ami-event event)))))
      (reset! server
              (run-jetty
               (-> (routes (var main-routes) (route/not-found "Page not found")))
               {:port 8082 :join? false})))))

(defn stop []
  (if-not @server
    "Server already stopped."
    (do (doto @server .stop .join)
        (reset! server nil))))

