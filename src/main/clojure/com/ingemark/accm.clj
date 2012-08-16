(ns com.ingemark.accm
  (:require (com.ingemark.clojure (logger :refer :all))
            (clojure.data (json :as json :refer (read-json json-str)))
            (clojure.java (io :as io))
            (com.ingemark.accm (config :as config)
                               (auth :as auth)
                               (service :as service)
                               (filters :as filters))
            (compojure (core :refer :all)
                       (route :as route))
            (com.ingemark (ring-jetty-adapter :refer (run-jetty)))
            (slingshot (slingshot :refer (try+)))))

(defonce server (atom nil))

(defn from-json [stream] (with-open [in (io/reader stream)] (json/read-json in)))

(defroutes main-routes
  (GET "/eventstream/:id" [id] (service/stream-events id))
  (POST "/eventstream" {body :body}
    (let [{:keys [filter extension]} (from-json body)
          stream-id (service/create-event-stream (case filter
                                                   "ccagent" filters/ccagent-filter
                                                   (throw (RuntimeException. "Invalid filter"))))]
      (when stream-id
        {:status 200
         :headers {"Location" (format "/eventstream/%s" stream-id)}}))) ; FIXME: Location URL MUST be absolute!
  (POST "/action" request (service/handle-action request))
  (POST "/manager-action" request (service/handle-manager-action request)))

(defn wrap-error-handling [ring-handler]
  (fn [request]
    (try+
      (ring-handler request)
      (catch [:reason :bad-request] _
        {:status 400
         :body (:message &throw-context)
         :headers {"Content-Type" "text/plain; charset=utf-8"}})
      (catch Exception e
        (logerror (pprint-str e))
        {:status 500 :body (.getMessage e) :headers {"Content-Type" "text/plain"}}))))

(defn wrap-basic-authentication [ring-handler]
  (auth/basic-auth-wrapper (auth/make-unauthorized-response-fn "Secure Area")
                           (fn [username password]
                             (some (fn [{u :username p :password}]
                                     (and (= u username) (= p password)))
                                   (config/settings :users)))
                           nil
                           ring-handler))

(defn main []
  (config/initialize)
  (if @server
    "Server already running"
    (do
      (service/initialize)
      (reset! server
              (run-jetty
               (-> (routes (var main-routes) (route/not-found "Page not found"))
                   wrap-basic-authentication
                   wrap-error-handling)
               {:port 8082 :join? false})))))

(defn stop []
  (if-not @server
    "Server already stopped."
    (do (doto @server .stop .join)
        (reset! server nil))))