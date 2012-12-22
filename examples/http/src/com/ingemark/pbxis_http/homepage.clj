(ns com.ingemark.pbxis-http.homepage
  (require [clojure.string :as s] [ring.util.response :as r]
           [clojure.core.strint :refer (<<)]
           [hiccup [core :as h] [element :as e] [page :as p]]))

(defn- ids [agnts qs]
  (s/join "," (concat (for [ag agnts, q qs] (<< "'~{ag}_~{q}_agent_status'")),
                      (for [ag agnts] (<< "'~{ag}_ext_status'")),
                      (for [ag agnts] (<< "'~{ag}_phone_num'")),
                      (for [q qs] (<< "'~{q}_queue_count'")))))

(defn homepage [type agnts qs]
  (->
   (p/html5
    {:xml? true}
    [:head
     [:title "PBXIS"]
     [:meta {:http-equiv "Content-Type" :content "text/html; charset=UTF-8"}]]
    (p/include-js
     "http://ajax.googleapis.com/ajax/libs/jquery/1.7.1/jquery.min.js"
     "/pbxis-client.js" (<< "/pbxis-~{type}.js"))
    (e/javascript-tag (<< "
  function pbx_connection(is_connected) {
    $('#connection').html(is_connected? 'Connected' : 'Disconnected');
    if (!is_connected)
      $.each([~(ids agnts qs)], function(_,id) { $('#'+id).html('---'); })
  }
  function pbx_agent_status(agent, queue, status) {
    $('#' + agent + '_' + queue + '_agent_status').html(status);
  }
  function pbx_extension_status(agent, status) {
    $('#' + agent + '_ext_status').html(status);
  }
  function pbx_queue_count(queue, count) {
    $('#' + queue + '_queue_count').html(count)
  }
  function pbx_phone_num(agent, num) {
    $('#' + agent + '_phone_num').html(num);
  }
  $(function() {
    pbx_connection(false);
    $('#originate').submit(function() {
      $.ajax({
        type: 'POST',
        url: '/originate/'+$('#src').val()+'/'+$('#dest').val(),
        contentType: 'application/json; charset=utf-8',
        dataType: 'json'
      });
    });
    pbx_start(
       [~(s/join \",\" (for [ag agnts] (str \\' ag \\')))],
       [~(s/join \",\" (for [q qs] (str \\' q \\')))]);
  })
"))
    [:body
     [:h3 [:span {:id "connection"} "Disconnected"]]
     (for [q qs]
       [:p (<< "Calls waiting on Queue ~{q}: ") [:span {:id (<< "~{q}_queue_count")} "---"]])
     [:form {:id "originate" :onsubmit "return false;"}
      [:label {:for "src"}] [:input {:id "src"}]
      [:label {:for "dest"} "--->"] [:input {:id "dest"}]
      [:input {:type "submit" :value "Call!"}]]
     [:table
      (for [agrow (partition-all 4 agnts)]
        (list
         [:tr (for [ag agrow] [:th (<< "Agent ~{ag}")])]
         [:tr (for [ag agrow]
                [:td
                 [:table {:border "1px"}
                  [:tr [:td {:align "right"} "Extension status"]
                   [:td [:span {:id (<< "~{ag}_ext_status")}]]]
                  [:tr [:td {:align "right"} "Phone number"]
                   [:td [:span {:id (<< "~{ag}_phone_num")}]]]
                  (for [q qs]
                    [:tr [:td {:align "right"} (<< "Status in queue ~{q}")]
                     [:td [:span {:id (<< "~{ag}_~{q}_agent_status")}]]])]])]))]])
   r/response (r/content-type "text/html") (r/charset "UTF-8")))

