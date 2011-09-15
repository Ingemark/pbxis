(ns com.ingemark.accm
  (:use com.ingemark.clojure.logger
        com.ingemark.accm.types
        (com.ingemark.accm (config :as config :only ())
                           (auth :as auth :only ()))
        (compojure core [route :as route])
        [com.ingemark.ring-jetty-adapter :only (run-jetty)]
        (ring.util [servlet :as servlet])
        [ring.middleware file file-info [params :only (wrap-params)]]
        (clojure.contrib (json :only (json-str read-json print-json))
                         (condition :as cnd :only ())))
  (:import (org.mortbay.jetty Server Request Response)
           (org.mortbay.jetty.handler AbstractHandler)
           (org.asteriskjava.manager ManagerConnectionFactory ManagerEventListener)
           (org.asteriskjava.manager.action QueueAddAction QueueRemoveAction)
           (org.asteriskjava.manager.event ManagerEvent JoinEvent LeaveEvent BridgeEvent HangupEvent QueueMemberStatusEvent
                                           AgentCalledEvent AgentConnectEvent AgentCompleteEvent ExtensionStatusEvent)
           (java.util.concurrent LinkedBlockingQueue BlockingQueue TimeUnit)
           (java.io Writer)
           (com.ingemark.accm.types ApplicationState User Listener AgentState)))

(defonce server (atom nil))

(defonce appstate (atom (ApplicationState. nil nil)))

(def listeners (atom [])) ; Seq<Listener>

(def call-database (atom {})) ; ::unique-id -> agentCalled

(def caller-id-database (atom {})) ; ::unique-id -> caller Id

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

(defroutes main-routes
  (GET "/eventstream" request (stream-events request (:user auth/*security-context*)))
  (GET "/extension/:ext/eventstream" request (stream-events request (:user auth/*security-context*)))
  (POST "/agent/active" request (ok ""))
  (GET "/agent/active" request (ok ""))
  (DELETE "/agent/active" request (ok "")))

(defn get-event-properties [event]
  (reduce (fn [x y] (assoc x
                      (keyword (let [method-name (.substring (.getName y) 3)]
                                 (str (Character/toLowerCase (first method-name))
                                      (.substring method-name 1))))
                      (.invoke y event nil)))
          {}
          (filter (fn [x] (and
                           (.startsWith (.getName x) "get")
                           (= (count (.getParameterTypes x)) 0)))
                  (.getMethods (class event)))))



(defn format-event [event]
  {:event "Unhandled" :name (.getName (class event))})



(defn- includes? [vector elem]
  (some #(= % elem) vector))

(defn- matches-extension? [listener extension]
  (= (fqext (:extension listener)) extension))

(defn- filter-listeners [filter-function]
  (filter (fn [listener]
            (or (includes? (-> listener :user :permissions) :view-all-events)
                (filter-function listener)))
          @listeners))

(defn process-event
  "Returns a list of listeners who should be notified of the event."
  [event]
  (condp instance? event
    JoinEvent (do (swap! caller-id-database assoc (.getUniqueId event)
                         {:caller-id (.getCallerIdNum event)})
                  [(filter-listeners identity) {:event "Join"
                                                :count (.getCount event)
                                                :unique-id (.getUniqueId event)}])

    LeaveEvent [(filter-listeners identity) {:event "Leave"
                                             :count (.getCount event)
                                             :unique-id (.getUniqueId event)}]

    AgentCalledEvent (do (swap! call-database assoc (.getUniqueId event) (.getAgentCalled event))
                         [(filter-listeners #(matches-extension? % (.getAgentCalled event)))
                          {:event "Ringing"
                           :caller-id (:caller-id (get @caller-id-database (.getUniqueId event)))
                           :unique-id (.getUniqueId event)}])

    AgentCompleteEvent (do (logdebug "COMPLETEEVENT" (get-event-properties event))
                           [@listeners nil])

    HangupEvent (do (swap! caller-id-database dissoc (.getUniqueId event))
                    [(filter-listeners #(matches-extension? % (get @call-database (.getUniqueId event))))
                     {:event "Hangup"
                      :unique-id (.getUniqueId event)}])
    nil))

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
  (if @server
    "Server already running"
    (do
      (let [factory (ManagerConnectionFactory. (-> config/settings :ami :ip-address)
                                               (-> config/settings :ami :username)
                                               (-> config/settings :ami :password))
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
