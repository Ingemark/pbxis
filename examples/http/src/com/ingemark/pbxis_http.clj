(ns com.ingemark.pbxis-http
  (require (com.ingemark [pbxis :as px] [logging :refer :all])
           [clojure.java.io :as io] [clojure.string :as s]
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
                :body (m/map* #(str "data:" (af/encode-json->string %) "\n\n") r)}
               ((if (nil? r) r/not-found r/response) (af/encode-json->string r)))))))

(defn html [type agnt]
  (fn [_] (-> (str "client.html") slurp
              (s/replace "$pbxis.js$" (str "/pbxis-" type ".js"))
              (s/replace "$agnt$" agnt)
              r/response (r/content-type "text/html") (r/charset "UTF-8"))))

(defn websocket-events [ticket]
  (fn [ch _]
    (m/ground ch)
    (doto (m/map* af/encode-json->string (m/channel))
      (px/attach-sink ticket)
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
    [[] {:post [{:strs [queues]} (ok px/config-agnt key queues)]}
     ["long-poll"] {:get (ok px/long-poll key)}
     ["websocket"] {:get (ah/wrap-aleph-handler (websocket-events key))}
     ["sse"] {:get (ok #(doto (m/channel) (px/attach-sink key)))}
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
  (let [cfg (into {} (doto (java.util.Properties.) (.load (io/reader "pbxis.properties"))))
        parse-int #(try (Integer/parseInt ^String %) (catch NumberFormatException _ nil))
        cfg (reduce #(update-in %1 [%2] parse-int) cfg
                    ["http-port" "originate-timeout-seconds" "poll-timeout-seconds"
                     "unsub-delay-seconds" "agent-gc-delay-minutes"])
        cfg (into {} (remove #(nil? (val %)) cfg))
        {:strs [http-port ami-host ami-username ami-password]} cfg
        cfg (dissoc cfg "http-port" "ami-host" "ami-username" "ami-password")]
    (px/ami-connect ami-host ami-username ami-password cfg)
    (reset! stop-server (ah/start-http-server (-> (var app-main)
                                                  (wrap-file "static-content")
                                                  wrap-file-info
                                                  ah/wrap-ring-handler)
                                              {:port http-port :websocket true}))))
