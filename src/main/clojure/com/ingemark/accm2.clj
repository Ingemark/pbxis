(ns com.ingemark.accm2
  (:use com.ingemark.clojure.logger)
  (:import (java.util.concurrent LinkedBlockingQueue Executors ExecutorService)
           (java.io OutputStream InputStream)
           java.net.Socket
           (org.asteriskjava.manager ManagerConnectionFactory ManagerConnection
                                     ManagerEventListener)
           (org.asteriskjava.manager.event ManagerEvent)))

(defrecord AppState [^ManagerConnectionFactory manager-connection-factory
                     ^ManagerConnection manager-connection])

(defonce appstate (atom (AppState. nil nil)))

(defrecord CCAgent [^String extension
                    ^ExecutorService event-processing-executor
                    ^Socket socket])

(defonce ^ExecutorService executor (Executors/newCachedThreadPool))

(def ccagents (atom []))

(def server-port 8081)

(defn deliver-event [agent event]
  (logdebug "sending via socket:" (:extension agent) (:class event)))

(defn- concerns?
  "Return true if event concerns agent."
  [event agent]
  true)

(defn- handle-ami-event
  "Called on each AMI event that we receive from asterisk-java framework."
  [^ManagerEvent event]
  (doseq [agnt @ccagents]
    (when (concerns? event agnt)
      (.execute (:event-processing-executor agnt) #(deliver-event agnt (bean event))))))

(defn- read-message [socket]
  {:id "login" :extension "204"})

(defn- authenticate
  "Performs authentication on socket, and returns the extension of the
  authenticated agent."
  [^Socket socket]
  (loop []
    (let [message (read-message socket)]
      (if (and (= "login" (:id message))
               (:extension message))
        (:extension message)
        (recur)))))

(defn- activate-agent [^Socket socket ^String extension]
  (swap! ccagents conj (CCAgent. extension (Executors/newSingleThreadExecutor) socket)))

(defn ccagent-connection-handler
  "Called as soon as the connection initiated by an agent has been established."
  [^Socket socket]
  (logdebug "accepted connection from" socket)
  (when-let [extension (authenticate socket)]
    (loginfo "agent" extension "logged in")
    (activate-agent socket extension)))

(defn server-loop []
  (let [server-socket (java.net.ServerSocket. server-port)]
    (try
      (loop []
        (let [s (.accept server-socket)]
          (.execute executor #(ccagent-connection-handler s)))
        (recur))
      (catch java.io.IOException e
        (.shutdown executor))
      (finally
       (.close server-socket)))))

(defn shutdown []
  (.shutdown executor)
  (doseq [a @ccagents]
    (.shutdown (:executor a)))
  (.logoff (:manager-connection @appstate)))

(defn main []
  (let [factory (ManagerConnectionFactory. "192.168.18.30" "manager" "oracle")
        connection (.createManagerConnection factory)]
    (swap! appstate assoc :manager-connection-factory factory :manager-connection connection)
    (reset! ccagents [])
    (.login connection)
    (.addEventListener connection (proxy [ManagerEventListener] []
                                    (onManagerEvent [event] (handle-ami-event event))))
    (.execute executor server-loop)))