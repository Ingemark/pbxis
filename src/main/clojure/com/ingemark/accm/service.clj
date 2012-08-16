(ns com.ingemark.accm.service
  (:require (com.ingemark.clojure (logger :refer :all))
            (com.ingemark.accm (types :refer :all))
            (com.ingemark.accm (config :as config)
                               (auth :as auth))
            (clojure.java (io :as io))
            (clojure.data (json :refer (read-json write-json json-str Write-JSON))))
  (:import (org.asteriskjava.manager ManagerConnectionFactory ManagerEventListener)
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

(defn ok
  "Utility function for returning 200 OK responses."
  ([] (ok nil nil))
  ([document]
     (ok nil document))
  ([content-type document]
     (merge {:status 200}
            (when document
              {:headers {"Content-Type" (or content-type "application/json; charset=utf-8") }
               :body document}))))

(defn create-event-stream []
  {:status 200})

(defn- register [^Listener listener]
  (swap! listeners conj listener))

(defn- unregister [^Listener listener]
  (swap! listeners #(remove %2 %1) #(= % listener)))

(defn transmit [^BlockingQueue q ^Writer w]
  (loop []
    (when-let [msg (.poll q 30 TimeUnit/SECONDS)]
      (.write w msg)
      (.flush w))
    (when-not (.checkError w)
      (recur))))

(defn stream-events [request]
  (let [q          (LinkedBlockingQueue. [hello-message])
        w          (.getWriter (:response request))
        context-id (-> request :params :id)
        listener   (Listener. context-id q)]
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

(defn handle-action [request]
  (let [action (read-json (io/reader (:body request)) false)]
    (case (get action "name")
      "originate" (logdebug "Originating" action auth/*security-context*)
      (logdebug "Unknown action"))
    (ok "")))

(defn handle-manager-action [request]
  (let [action (read-json (io/reader (:body request)) false)]
    (case (get action "name")
          "originate" (originate-call action)
          (logdebug "Unknown action"))
    (ok "")))

;; This overrides the default implementation of clojure.contrib.json/write-json-generic
;; so that we don't break on objects which cannot be serialized into JSON.
(extend java.lang.Object Write-JSON
        {:write-json (fn [x out escape-unicode?]
                       (if (.isArray (class x))
                         (write-json (seq x) out escape-unicode?)
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

(defn initialize []
  (let [factory (ManagerConnectionFactory. (-> (config/settings) :ami :ip-address)
                                           (-> (config/settings) :ami :username)
                                           (-> (config/settings) :ami :password))
        connection (.createManagerConnection factory)]
    (swap! appstate assoc :manager-connection-factory factory :manager-connection connection)
    (.login connection)
    (.addEventListener connection (proxy [ManagerEventListener] []
                                    (onManagerEvent [event] (handle-ami-event event))))))