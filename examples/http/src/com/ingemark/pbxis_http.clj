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
                                   (str "event: " (:type %) "\n"
                                        "data: " (af/encode-json->string (dissoc % :type)) "\n"
                                        "\n"))
                                     r)}
               ((if (nil? r) r/not-found r/response) (af/encode-json->string r)))))))

(defn html [type agnt]
  (fn [_] (-> (str "client.html") slurp
              (s/replace "$pbxis-adapter.js$" (str "/pbxis-" type ".js"))
              (s/replace "$agnt$" agnt)
              r/response (r/content-type "text/html") (r/charset "UTF-8"))))

(defn ticket [] (-> (java.util.UUID/randomUUID) .toString))

(def ticket->eventch (atom {}))

(def poll-timeout (atom 30000))
(defn- unsub-delay [] [15 TimeUnit/SECONDS])
(defn- agnt-gc-delay [] [60 TimeUnit/MINUTES])
(def EVENT-BURST-MILLIS 100)

(defn- set-ticket-invalidator-schedule [tkt reschedule?]
  (let [newsched
        (when reschedule? (apply px/schedule
                                 (fn [] (swap! ticket->eventch
                                               #(do (-?> (% ticket) :eventch px/close-permanent)
                                                    (dissoc % ticket))))
                                 (unsub-delay)))]
    (swap! ticket->eventch update-in [tkt :invalidator] #(do (px/cancel-schedule %) newsched))))

(defn event-channel [agnts qs]
  (let [tkt (ticket), eventch (px/event-channel agnts qs)]
    (swap! ticket->eventch assoc-in [tkt :eventch] eventch)
    (set-ticket-invalidator-schedule tkt true)
    tkt))

(defn attach-sink
  [sinkch tkt]
  (when-let [eventch (and sinkch (>?> @ticket->eventch tkt :eventch))]
    (logdebug "Attach sink" agnt)
    (m/on-closed sinkch #(do (logdebug "Closed sink" agnt)
                             (set-ticket-invalidator-schedule tkt true)))
    (m/siphon eventch sinkch)
    (set-ticket-invalidator-schedule tkt false)
    sinkch))

(defn long-poll [ticket]
  (logdebug "long-poll" ticket)
  (when-let [eventch (>?> @ticket->eventch ticket :eventch)]
    (when-let [sinkch (attach-sink (m/channel) agnt)]
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
    (doto (->> (attach-sink (m/channel) ticket)
               (m/map* #(spy "Send WebSocket" (af/encode-json->string %))))
      (m/join ch))))

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
     ["sse"] {:get (ok #(doto (m/channel) (attach-sink (@ticket->agnt key))))}
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
