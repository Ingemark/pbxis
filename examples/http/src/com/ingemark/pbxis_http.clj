(ns com.ingemark.pbxis-http
  (require (com.ingemark [pbxis :as px, :refer (>?>)] [logging :refer :all])
           [com.ingemark.pbxis-http.homepage :refer :all]
           [clojure.java.io :as io] [clojure.string :as s] [clojure.walk :refer (keywordize-keys)]
           [clojure.core.incubator :refer (-?> dissoc-in)]
           [ring.util.response :as r]
           [net.cgrand.moustache :refer (app)]
           (aleph [http :as ah] [formats :as af])
           [lamina.core :as m]
           (ring.middleware [json-params :refer (wrap-json-params)]
                            [file :refer (wrap-file)]
                            [file-info :refer (wrap-file-info)]))
  (import java.util.concurrent.TimeUnit))

(defonce poll-timeout (atom [30 TimeUnit/SECONDS]))
(defonce unsub-delay (atom [15 TimeUnit/SECONDS]))
(def EVENT-BURST-MILLIS 100)

(defn- ok [f & args]
  (fn [_] (m/run-pipeline
           (apply f args)
           (fn [r]
             (if (m/channel? r)
              {:status 200, :headers {"Content-Type" "text/event-stream"}
               :body (m/map* #(do (logdebug "Send SSE" %)
                                  (str "event: " (:type %) "\n"
                                       "data: " (af/encode-json->string (dissoc % :type)) "\n"
                                       "\n"))
                             r)}
              ((if (nil? r) r/not-found r/response) (af/encode-json->string r)))))))

(defn- ticket [] (spy "New ticket" (-> (java.util.UUID/randomUUID) .toString)))

(def ticket->eventch (atom {}))

(defn- invalidate-ticket [tkt]
  (loginfo "Invalidate ticket" tkt)
  (swap! ticket->eventch #(do (-?> (% tkt) :eventch px/close-permanent)
                              (dissoc % tkt))))

(defn- set-ticket-invalidator-schedule [tkt reschedule?]
  (let [newsched
        (when reschedule?
          (logdebug "Schedule invalidator" tkt)
          (apply px/schedule #(invalidate-ticket tkt) @unsub-delay))]
    (swap! ticket->eventch update-in [tkt :invalidator]
           #(do (when % (logdebug "Cancel invalidator" tkt) (px/cancel-schedule %))
                newsched))))

(defn- ticket-for [agnts qs]
  (let [tkt (ticket), eventch (px/event-channel agnts qs)]
    (swap! ticket->eventch assoc-in [tkt :eventch] eventch)
    (m/on-closed eventch #(swap! ticket->eventch dissoc tkt))
    (set-ticket-invalidator-schedule tkt true)
    tkt))

(defn- attach-sink [sinkch tkt]
  (when-let [eventch (and sinkch (>?> @ticket->eventch tkt :eventch))]
    (logdebug "Attach sink" tkt)
    (m/on-closed sinkch #(do (logdebug "Closed sink" tkt)
                             (set-ticket-invalidator-schedule tkt true)))
    (m/siphon eventch sinkch)
    (set-ticket-invalidator-schedule tkt false)
    sinkch))

(defn- sse-channel [tkt]
  (let [sinkch (m/channel)]
    (or (attach-sink sinkch tkt)
        (doto sinkch (m/enqueue {:type "closed"}) m/close))))

(defn- long-poll [tkt]
  (logdebug "long-poll" tkt)
  (when-let [eventch (>?> @ticket->eventch tkt :eventch)]
    (when-let [sinkch (attach-sink (m/channel) tkt)]
      (let [finally #(do (m/close sinkch) %)]
        (if-let [evs (m/channel->seq sinkch)]
          (finally (m/success-result (vec evs)))
          (m/run-pipeline (m/read-channel*
                           sinkch :timeout (apply px/to-millis @poll-timeout), :on-timeout :xx)
                          {:error-handler finally}
                          (m/wait-stage EVENT-BURST-MILLIS)
                          #(vec (let [evs (m/channel->seq sinkch)]
                                  (if (not= % :xx) (conj evs %) evs)))
                          finally))))))

(defn- websocket-events [ticket]
  (fn [ch _]
    (m/ground ch)
    (doto (->> (attach-sink (m/channel) ticket)
               (m/map* #(spy "Send WebSocket" (af/encode-json->string %))))
      (m/join ch))))

(defn- wrap-log-request [handle] #(->> % (spy "HTTP request") handle (spy "HTTP response")))

(defonce stop-server (atom nil))

(defn stop []
  (loginfo "Shutting down")
  (future
    (px/ami-disconnect)
    (when @stop-server @(@stop-server) (reset! stop-server nil)))
  "pbxis service shutting down")

(defn- split [s] (s/split s #","))

(def app-main
  (app
   ["client" type [agnts split] [qs split]] {:get (fn [_] (homepage type agnts qs))}
   ["stop"] {:post (ok stop)}
   [&]
   [wrap-json-params
    ["ticket"] {:post [{:strs [agents queues]} (ok ticket-for agents queues)]}
    ["originate" src dest] {:post (ok px/originate-call src dest)}
    ["queue" action agnt] {:post [{:as params}
                                  (ok px/queue-action action agnt
                                      (select-keys params
                                                   (into ["queue"]
                                                         (condp = action
                                                           "add" ["memberName" "paused"]
                                                           "pause" ["paused"]
                                                           "remove" []))))]}
    [ticket &]
    [["long-poll"] {:get (ok long-poll ticket)}
     ["websocket"] {:get (ah/wrap-aleph-handler (websocket-events ticket))}
     ["sse"] {:get (ok #(sse-channel ticket))}]]))

(defn main []
  (System/setProperty "logback.configurationFile" "logback.xml")
  (let [cfg (->> (doto (java.util.Properties.) (.load (io/reader "pbxis.properties")))
                 (into {})
                 keywordize-keys)
        parse-int #(try (Integer/parseInt ^String %) (catch NumberFormatException _ nil))
        cfg (reduce #(update-in %1 [%2] parse-int) cfg
                    [:http-port :originate-timeout-seconds :poll-timeout-seconds
                     :unsub-delay-seconds])
        cfg (into {} (remove #(nil? (val %)) cfg))
        {:keys [http-port ami-host ami-username ami-password]} cfg
        cfg (dissoc (spy "Configuration" cfg) :http-port :ami-host :ami-username :ami-password)]
    (swap! poll-timeout #(if-let [t (cfg :poll-timeout-seconds)] [t TimeUnit/SECONDS] %))
    (swap! unsub-delay #(if-let [d (cfg :unsub-delay-seconds)] [d TimeUnit/SECONDS] %))
    (px/ami-connect ami-host ami-username ami-password cfg)
    (reset! stop-server (ah/start-http-server (-> (var app-main)
                                                  (wrap-file "static-content")
                                                  wrap-file-info
                                                  ah/wrap-ring-handler)
                                              {:port http-port :websocket true}))))
