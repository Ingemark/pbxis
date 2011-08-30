(ns com.ingemark.compojure
  (:use (compojure core [route :as route])
        [com.ingemark.ring-jetty-adapter :only (run-jetty)]
        (ring.util [servlet :as servlet])
        [ring.middleware file file-info [params :only (wrap-params)]]
        [clojure.contrib.json :only (json-str read-json)])
  (:import (org.mortbay.jetty Server Request Response)
           (org.mortbay.jetty.handler AbstractHandler)))

(defn m [cnt] (str "{\"id\":\"" cnt "\", \"type\":\"update\"}"))

(defn- printout [w]
  (doseq [cnt (range 30)]
    (.write w (.getBytes "A"))
    (.flush w)
    (Thread/sleep 15000)))

(defroutes main-routes
  (GET "/events" request
       (do
         (when-let [response (:response request)]
           (when-let [w (.getOutputStream response)]
             (printout w)))
         {:status 200 :body (java.io.ByteArrayInputStream. (.getBytes ""))}))
  (POST "/call" request
        {:status 200 :body "Call originated."}))

(defonce server (atom nil))

(defn main []
  (if @server
    "Server already running"
    (reset! server
            (run-jetty
             (-> (routes (var main-routes) (route/not-found "Page not found")))
             {:port 8082 :join? false}))))

(defn stop []
  (if-not @server
    "Server already stopped."
    (do (doto @server .stop .join)
        (reset! server nil))))

(defn connect []
  (let [client (org.apache.http.impl.client.DefaultHttpClient.)
        method (org.apache.http.client.methods.HttpGet. "http://localhost:8082/events")
        response (.execute client method)
        entity (.getEntity response)]
    entity))