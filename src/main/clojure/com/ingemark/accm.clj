(ns com.ingemark.accm
  (:use com.ingemark.clojure.logger
        com.ingemark.accm.types
        (com.ingemark.accm (config :as config :only ())
                           (auth :as auth :only ()))
        (compojure core [route :as route])
        (clojure.contrib (io :as io :only ()))
        [com.ingemark.ring-jetty-adapter :only (run-jetty)]
        (ring.util [servlet :as servlet])
        [ring.middleware file file-info [params :only (wrap-params)]]
        (clojure.contrib (json :only (read-json write-json json-str Write-JSON))
                         (condition :as cnd :only ())))
  (:import (org.mortbay.jetty Server Request Response)
           (org.mortbay.jetty.handler AbstractHandler)
           (org.asteriskjava.manager ManagerConnectionFactory ManagerEventListener)
           (org.asteriskjava.manager.event ManagerEvent)
           (org.asteriskjava.manager.response ManagerResponse)
           (org.asteriskjava.manager.action QueueAddAction QueueRemoveAction OriginateAction)
           (java.util.concurrent LinkedBlockingQueue BlockingQueue TimeUnit)
           (java.io Writer)
           (com.ingemark.accm.types ApplicationState User Listener) ))

(defonce server (atom nil))

(defonce appstate (atom (ApplicationState. nil nil)))

(def listeners (atom [])) ; Seq<Listener>

(def hello-message (json-str {:event "Hello"}))

(defn transmit [^BlockingQueue q ^Writer w]
  (loop []
    (when-let [msg (.poll q 30 TimeUnit/SECONDS)]
      (.write w msg)
      (.flush w))
    (when-not (.checkError w)
      (recur))))

(defn ok
  ([] (ok nil nil))
  ([document]
     (ok nil document))
  ([content-type document]
     (merge {:status 200}
            (when document
              {:headers {"Content-Type" (or content-type "application/json; charset=utf-8") }
               :body document}))))

(defn wrap-error-handling [ring-handler]
  (fn [request]
    (try
      (cnd/handler-case :reason
                        (ring-handler request)
                        (handle :bad-request
                          {:status 400 :body (cnd/*condition* :message) :headers {"Content-Type" "text/plain; charset=utf-8"}}))
      (catch Exception e
        {:status 500 :body (pprint-str e) :headers {"Content-Type" "text/plain"}}))))

(defn- register [^Listener listener]
  (swap! listeners conj listener))

(defn- unregister [^Listener listener]
  (swap! listeners #(remove %2 %1) #(= % listener)))

(defn stream-events [request user]
  (let [q         (LinkedBlockingQueue. [hello-message])
        w         (.getWriter (:response request))
        extension (-> request :params :extension)
        listener  (Listener. user extension q)]
    (register listener)
    (try
      (transmit q w)
      (ok "")
      (finally
       (unregister listener)))))

(defn- fqext [extension]
  (str "SIP/" extension))

(defn add-agent-to-queue [extension queue]
  (.sendAction (:manager-connection @appstate) (QueueAddAction. queue (fqext extension))))

(defn remove-agent-from-queue [extension queue]
  (.sendAction (:manager-connection @appstate) (QueueRemoveAction. queue (fqext extension))))

(defn- originate-call [{channel  "channel"
                        context  "context"
                        exten    "exten"
                        timeout  "timeout"
                        priority "priority"
                        async    "async"
                        application "application"
                        callerid    "callerid"
                        :or {context (config/settings :default-outgoing-context)
                             timeout (config/settings :default-originate-timeout)
                             priority 1
                             async false}}]
  (let [oa (OriginateAction.)]
    (doto oa
      (.setChannel channel)
      (.setContext context)
      (.setExten   exten)
      (.setTimeout timeout)
      (.setPriority priority)
      (.setAsync    async)
      (.setCallerId callerid))
    (let [^ManagerResponse response (.sendAction (:manager-connection @appstate) oa (long 0))]
      (logdebug "Got response" (.getMessage response)))))

(defn- handle-action [request]
  (let [action (read-json (io/reader (:body request)) false)]
    (case (get action "name")
          "originate" (logdebug "Originating" action auth/*security-context*)
          (logdebug "Unknown action"))
    (ok "")))

(defn- handle-manager-action [request]
  (let [action (read-json (io/reader (:body request)) false)]
    (case (get action "name")
          "originate" (originate-call action)
          (logdebug "Unknown action"))
    (ok "")))

(defroutes main-routes
  (GET "/eventstream" request (stream-events request (:user auth/*security-context*)))
  (POST "/action" request (handle-action request))
  (POST "/manager-action" request (handle-manager-action request)))

;; This overrides the default implementation of clojure.contrib.json/write-json-generic
;; so that we don't break on objects which cannot be serialized into JSON.
(extend java.lang.Object Write-JSON
        {:write-json (fn [x out]
                       (if (.isArray (class x))
                         (write-json (seq x) out)
                         (.print out (str "\"" x "\""))))})

(defn- field-name [^java.lang.reflect.Method getter-or-setter]
  (str (Character/toLowerCase (.charAt (.getName getter-or-setter) 3))
       (.substring (.getName getter-or-setter) 4)))

(defn get-event-properties [event]
  (reduce (fn [acc getter] (assoc acc
                             (keyword (field-name getter))
                             (.invoke getter event nil)))
          {}
          (filter #(and (.startsWith (.getName %) "get")
                        (= (count (.getParameterTypes %)) 0))
                  (.getMethods (class event)))))

(defn process-event
  "Returns a list of listeners who should be notified of the event, and event itself."
  [event]
  (let [e (merge {:event (.getName (class event))}
                 (get-event-properties event))]
    [@listeners e]))

(defn- handle-ami-event
  "Called on each AMI event that we receive from asterisk-java framework."
  [^ManagerEvent event]
  (logdebug "AMI event" (.getName (class event)))
  (let [[listeners message] (process-event event)]
    (doseq [^Listener listener listeners]
      (.offer (:queue listener) (json-str message)))))

(defn- authenticate [username password]
  (some (fn [{u :username p :password}]
          (and (= u username) (= p password)))
        (config/settings :users)))

(defn wrap-basic-authentication [ring-handler]
  (auth/basic-auth-wrapper (auth/make-unauthorized-response-fn "Secure Area")
                           authenticate
                           nil
                           ring-handler))

(defn main []
  (config/initialize)
  (if @server
    "Server already running"
    (do
      (let [factory (ManagerConnectionFactory. (-> (config/settings) :ami :ip-address)
                                               (-> (config/settings) :ami :username)
                                               (-> (config/settings) :ami :password))
            connection (.createManagerConnection factory)]
        (swap! appstate assoc :manager-connection-factory factory :manager-connection connection)
        (.login connection)
        (.addEventListener connection (proxy [ManagerEventListener] []
                                        (onManagerEvent [event] (handle-ami-event event)))))
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

#_(let [parameters {"variables" {"k1" "v1"} "channel" "SIP/139" "async" false}
      klass (Class/forName "org.asteriskjava.manager.action.OriginateAction")
      methods (.getMethods klass)
      setters (filter #(= "set" (.substring (.getName %) 0 3)) methods)]
  (doseq [setter setters]
    (let [fname (field-name setter)]
      (when (contains? parameters fname)
        (logdebug "setting" fname)))))