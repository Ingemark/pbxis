(ns com.ingemark.pbxis-http
  (require (com.ingemark [pbxis :as px] [logging :refer :all])
           [clojure.java.io :as io] [clojure.string :as s] [clojure.walk :refer (keywordize-keys)]
           [ring.util.response :as r]
           [net.cgrand.moustache :refer (app)]
           (aleph [http :as ah] [formats :as af])
           [lamina.core :as m]
           (ring.middleware [json-params :refer (wrap-json-params)]
                            [file :refer (wrap-file)]
                            [file-info :refer (wrap-file-info)])))

(defn ok [f & args]
  (fn [_] (m/run-pipeline
           (apply f args)
           (fn [r]
             (if (m/channel? r)
               {:status 200
                :headers {"Content-Type" "text/event-stream"}
                :body (m/map* #(do (logdebug "Send SSE" %)
                                   (str "event: " (first %) "\n"
                                        "data: " (af/encode-json->string (next %)) "\n"
                                        "\n"))
                                     r)}
               ((if (nil? r) r/not-found r/response) (af/encode-json->string r)))))))

(defn html [type agnt]
  (fn [_] (-> (str "client.html") slurp
              (s/replace "$pbxis-adapter.js$" (str "/pbxis-" type ".js"))
              (s/replace "$agnt$" agnt)
              r/response (r/content-type "text/html") (r/charset "UTF-8"))))

(defn ticket [] (-> (java.util.UUID/randomUUID) .toString))

(def ticket->agnt (ref {}))
(def agnt->ticket (ref {}))
(def poll-timeout (atom 30000))
(def EVENT-BURST-MILLIS 100)

(defn config-agnt [agnt qs]
  (when-let [close-signal (px/config-agnt agnt qs)]
    (let [tkt (ticket)]
      (dosync
       (alter ticket->agnt dissoc (agnt->ticket agnt))
       (alter ticket->agnt assoc tkt agnt)
       (alter agnt->ticket assoc agnt tkt))
      (m/run-pipeline close-signal
                      (fn [_] (dosync (alter ticket->agnt dissoc tkt)
                                      (when (= ticket (agnt->ticket agnt))
                                        (dissoc agnt->ticket agnt)))))
      tkt)))

(defn long-poll [ticket]
  (logdebug "long-poll" ticket)
  (when-let [agnt (@ticket->agnt ticket)]
    (when-let [sinkch (px/attach-sink (m/channel) agnt)]
      (let [finally #(do (m/close sinkch) %)]
        (if-let [evs (m/channel->seq sinkch)]
          (finally (m/success-result (vec evs)))
          (m/run-pipeline (m/read-channel* sinkch :timeout @poll-timeout, :on-timeout :xx)
                          {:error-handler finally}
                          (m/wait-stage EVENT-BURST-MILLIS)
                          #(vec (let [evs (m/channel->seq sinkch)]
                                  (if (not= % :xx) (conj evs %) evs)))
                          finally))))))

(defn websocket-events [ticket]
  (fn [ch _]
    (m/ground ch)
    (-> (->> (px/attach-sink (m/channel) ticket)
             (m/map* #(spy "Send WebSocket" (af/encode-json->string %))))
        (m/siphon ch))))

(defn wrap-log-request [handle] #(->> % (spy "HTTP request") handle (spy "HTTP response")))

(defonce stop-server (atom nil))

(defn stop []
  (loginfo "Shutting down")
  (future
    (when @stop-server @(@stop-server) (reset! stop-server nil))
    (px/ami-disconnect))
  "pbxis service shutting down")

(def app-main
  (app
   ["client" type agnt] {:get (html type agnt)}
   [&]
   [wrap-json-params
    ["agent" key &]
    [[] {:post [{:strs [queues]} (ok config-agnt key queues)]}
     ["long-poll"] {:get (ok long-poll key)}
     ["websocket"] {:get (ah/wrap-aleph-handler (websocket-events (@ticket->agnt key)))}
     ["sse"] {:get (ok #(doto (m/channel) (px/attach-sink (@ticket->agnt key))))}
     ["originate"] {:post [{:strs [phone]} (ok px/originate-call key phone)]}
     ["queue" action] {:post [{:as params}
                              (ok px/queue-action action key
                                  (select-keys params
                                               (into ["queue"]
                                                     (condp = action
                                                       "add" ["memberName" "paused"]
                                                       "pause" ["paused"]
                                                       "remove" []))))]}]]
   ["stop"] {:post (ok stop)}))

(defn main []
  (System/setProperty "logback.configurationFile" "logback.xml")
  (let [cfg (->> (doto (java.util.Properties.) (.load (io/reader "pbxis.properties")))
                 (into {})
                 keywordize-keys)
        parse-int #(try (Integer/parseInt ^String %) (catch NumberFormatException _ nil))
        cfg (reduce #(update-in %1 [%2] parse-int) cfg
                    [:http-port :originate-timeout-seconds :poll-timeout-seconds
                     :unsub-delay-seconds :agent-gc-delay-minutes])
        cfg (into {} (remove #(nil? (val %)) cfg))
        {:keys [http-port ami-host ami-username ami-password]} cfg
        cfg (dissoc cfg :http-port :ami-host :ami-username :ami-password)]
    (swap! poll-timeout #(if-let [t (cfg :poll-timeout-seconds)] (* 1000 t) %))
    (px/ami-connect ami-host ami-username ami-password cfg)
    (reset! stop-server (ah/start-http-server (-> (var app-main)
                                                  (wrap-file "static-content")
                                                  wrap-file-info
                                                  ah/wrap-ring-handler)
                                              {:port http-port :websocket true}))))
