(ns com.ingemark.pbxis-http.homepage
  (require [clojure.string :as s] [ring.util.response :as r]
           [clojure.core.strint :refer (<<)]
           [hiccup [core :as h] [element :as e] [page :as p]]))

(defn- texts [agnts qs]
  (s/join "," (concat (for [ag agnts, q qs] (<< "'~{ag}_~{q}_agent_status'"))
                      (for [ag agnts] (<< "'~{ag}_phone_num'"))
                      (for [q qs] (<< "'~{q}_queue_count'")))))

(defn- ext-statuses [agnts]
  (s/join "," (for [ag agnts] (<< "'~{ag}_ext_status'"))))

(defn homepage [type agnts qs]
  (->
   (p/html5
    {:xml? true}
    [:head
     [:title "PBXIS"]
     [:meta {:http-equiv "Content-Type" :content "text/html; charset=UTF-8"}]]
    (p/include-css "/style.css")
    (p/include-js
     "http://ajax.googleapis.com/ajax/libs/jquery/1.7.1/jquery.min.js"
     "/pbxis-client.js" (<< "/pbxis-~{type}.js") "/homepage.js")
    (e/javascript-tag (<< "
      function pbx_connection(is_connected) {
        $('#connection').html(is_connected? 'Connected' : 'Disconnected');
        if (!is_connected) {
          $.each([~(texts agnts qs)], function(_,id) {
             $('#'+id).html('---');
          });
          $.each([~(ext-statuses agnts)], function(_,id) {
             $('#'+id).attr('src', '/img/not_inuse.png');
          });
        }
      }
      $(function() {
        pbx_start(
          [~(s/join \",\" (for [ag agnts] (str \\' ag \\')))],
          [~(s/join \",\" (for [q qs] (str \\' q \\')))]);
      });"))
    [:body
     [:h3 [:span {:id "connection"} "Disconnected"]]
     [:form {:id "originate" :onsubmit "return false;"}
      [:label {:for "src"}] [:input {:id "src"}]
      [:label {:for "dest"} "--->"] [:input {:id "dest"}]
      [:input {:type "submit" :value "Call!"}]]
     [:form {:id "queueaction" :onsubmit "return false;"}
      [:label {:for "agent"}] [:input {:id "agent"}]
      [:label {:for "queue"} "/"] [:input {:id "queue"}]
      [:img {:id "log-on" :src "/img/loggedon.png"}]
      [:img {:id "pause" :src "/img/paused.png"}]
      [:img {:id "log-off" :src "/img/loggedoff.png"}]]
     (for [q qs]
       [:p (<< "Queue ~{q}: ") [:span {:id (<< "~{q}_queue_count")} "---"]])
     [:table.outer
      (for [agrow (partition-all 4 agnts)]
        (list
         [:tr.outer (for [ag agrow] [:th.outer {:id (<< "~{ag}_name")} (<< "Agent ~{ag}")])]
         [:tr (for [ag agrow]
                [:td.outer
                 [:table {:border "1px"}
                  [:tr [:td {:align "right"} "Extension"]
                   [:td [:img {:id (<< "~{ag}_ext_status")}]]]
                  [:tr [:td {:align "right"} "Party"]
                   [:td [:span {:id (<< "~{ag}_phone_num")}]]]
                  (for [q qs]
                    [:tr [:td {:align "right"} q]
                     [:td [:img {:id (<< "~{ag}_~{q}_agent_status")}]]])]])]))]])
   r/response (r/content-type "text/html") (r/charset "UTF-8")))

