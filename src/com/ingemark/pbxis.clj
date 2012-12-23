;; Copyright 2012 Inge-mark d.o.o.
;;    Licensed under the Apache License, Version 2.0 (the "License");
;;    you may not use this file except in compliance with the License.
;;    You may obtain a copy of the License at
;;        http://www.apache.org/licenses/LICENSE-2.0
;;    Unless required by applicable law or agreed to in writing, software
;;    distributed under the License is distributed on an "AS IS" BASIS,
;;    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;;    See the License for the specific language governing permissions and
;;    limitations under the License.

(ns com.ingemark.pbxis
  (require [clojure.set :as set] [clojure.data :as d] [clojure.string :as s]
           [lamina.core :as m] [lamina.core.graph.node :as node]
           [lamina.core.channel :as chan] [lamina.executor :as ex]
           [com.ingemark.logging :refer :all]
           (clojure.core [incubator :refer (-?> -?>> dissoc-in)] [strint :refer (<<)]))
  (import lamina.core.graph.node.NodeState
          (org.asteriskjava.manager ManagerConnectionFactory ManagerEventListener)
          org.asteriskjava.manager.event.ManagerEvent
          (java.util.concurrent Executors TimeUnit TimeoutException)
          (clojure.lang Reflector RT)))

(defonce config (atom {}))

(def default-config {:location-prefix "SCCP/"
                     :originate-context "default"
                     :originate-timeout-seconds 45})

(defn- originate-timeout [] (@config :originate-timeout-seconds))
(def FORGET-PHONENUM-DELAY [3 TimeUnit/HOURS])
(def DUE-EVENT-WAIT-SECONDS 5)

(defonce ^:private lock (Object.))

(defonce ^:private ami-connection (atom nil))

(defonce ^:private event-hub (atom nil))

(defonce ^:private agnt-state (atom {}))

(defonce ^:private q-cnt (atom {}))

(defonce ^:private agnt-calls (atom {}))

(defonce ^:private memory (atom {}))

(defn >?> [& fs] (reduce #(when %1 (%1 %2)) fs))

(defn to-millis [^Long t ^TimeUnit unit] (.toMillis unit t))

(defn close-permanent [ch]
  (when-let [n (and ch (chan/receiver-node ch))]
    (node/set-state! n ^NodeState (node/state n) :permanent? false)
    (m/close ch)))

(defn invoke [^String m o & args]
  (clojure.lang.Reflector/invokeInstanceMethod o m (into-array Object args)))

(defn upcase-first [s]
  (let [^String s (if (instance? clojure.lang.Named s) (name s) (str s))]
    (if (empty? s) "" (str (Character/toUpperCase (.charAt s 0)) (.substring s 1)))))

(defn- event-bean [event]
  (into (sorted-map)
        (-> (bean event)
            (dissoc :dateReceived :application :server :priority :appData :func :class
                    :source :timestamp :line :file :sequenceNumber :internalActionId)
            (assoc :event-type
              (let [c (-> event .getClass .getSimpleName)]
                (.substring c 0 (- (.length c) (.length "Event"))))))))

(defn- action [type params]
  (let [a (Reflector/invokeConstructor
           (RT/classForName (<< "org.asteriskjava.manager.action.~{type}Action"))
           (object-array 0))]
    (doseq [[k v] params] (invoke (<< "set~(upcase-first k)") a v))
    a))

(defn- send-action [a & [timeout]]
  (let [r (spy 'ami-event "Action response"
               (->> (-> (if timeout
                          (.sendAction @ami-connection a timeout)
                          (.sendAction @ami-connection a))
                        bean (dissoc :attributes :class :dateReceived))
                    (remove #(nil? (val %)))
                    (into (sorted-map))))]
    (when (= (r :response) "Success") r)))

(defn- send-eventaction [a]
  (->>
   (doto (.sendEventGeneratingAction @ami-connection a)
     (-> .getResponse (#(logdebug 'ami-event "Manager response"
                                  (.getResponse %) "|" (.getMessage %)))))
   .getEvents
   (mapv event-bean)))

(defn schedule [task delay unit]
  (m/run-pipeline nil (m/wait-stage (to-millis delay unit)) (fn [_] (task))))

(defn cancel-schedule [async-result] (when async-result (m/enqueue async-result :cancel)))

(defn- remember [k v timeout]
  (swap! memory assoc k v)
  (schedule #(swap! memory dissoc k) timeout TimeUnit/SECONDS))

(defn- recall [k] (when-let [v (@memory k)] (swap! memory dissoc k) v))

(defn- agnt->location [agnt] (when agnt (str (@config :location-prefix) agnt)))

(defn- update-agnt-state [agnt f & args]
  (swap! agnt-state #(if (% agnt)
                       (apply update-in % [agnt] f args)
                       %)))

(defn- replace-in-agnt-state [agnt path new-val]
  (let [swapped-in (update-agnt-state agnt update-in path #(if-not (= % new-val) new-val %))]
    (identical? new-val (apply >?> swapped-in agnt path))))

(defn- set-schedule [task-atom agnt delay task]
  (let [newsched (when delay
                   (apply schedule #(swap! task-atom dissoc agnt) (task) delay))]
    (swap! task-atom update-in [agnt] #(do (cancel-schedule %) newsched))))

(def ^:private int->exten-status
  {0 "not_inuse" 1 "inuse" 2 "busy" 4 "unavailable" 8 "ringing" 16 "onhold"})

(defn- event->member-status [event]
  (let [p (:paused event), s (:status event)]
    (cond (nil? p) "loggedoff"
          (= 4 s) "invalid"
          (true? p) "paused"
          :else "loggedon"
          #_(condp = s
              0 "unknown"
              1 "not_inuse"
              2 "inuse"
              3 "busy"
              5 "unavailable"
              6 "ringing"
              7 "ringinuse"
              8 "onhold"))))

(defn- digits [s] (re-find #"\d+" (or s "")))

(defn- exten-status [agnt] (-> (action "ExtensionState" {:exten agnt :context "hint"})
                               send-action :status int->exten-status))

(defn- q-status [q agnt]
  (->> (send-eventaction (action "QueueStatus" {:member (agnt->location agnt) :queue q}))
       (reduce (fn [vect ev] (condp = (ev :event-type)
                               "QueueParams" (conj vect [ev])
                               "QueueMember" (conj (pop vect) (conj (peek vect) ev))
                               vect))
               [])))

(defn- agnt-q-status [agnt q]
  (let [[q-event member-event] (first (q-status q agnt))]
    (when q-event {:calls (:calls q-event), :status (event->member-status member-event)
                   :name (:memberName member-event)})))

(defn- calls-in-progress [& [agnt]]
  (let [r (reduce
           #(apply assoc-in %1 %2)
           {}
           (for [{:keys [bridgedChannel bridgedUniqueId callerId]}
                 (send-eventaction (action "Status" {}))
                 :let [ag (digits bridgedChannel)]
                 :when (if agnt (= ag agnt) true)]
             [[agnt bridgedUniqueId] (or callerId "")]))]
    (if agnt (r agnt) r)))

(defn- full-agnt-q-status []
  (loginfo "Fetching full Asterisk queue status")
  (let [agnts (keys @agnt-state) qs (keys @q-cnt), q-status (mapcat #(q-status % nil) qs)]
    {:q-cnt (into {} (for [[q-st] q-status] [(:queue q-st) (:calls q-st)]))
     :agnt-q-status (reduce (fn [m [q-st & members]]
                              (reduce #(let [agnt (digits (:location %2))]
                                         (-> %1
                                             (assoc-in [agnt (:queue q-st)]
                                                       (event->member-status %2))
                                             (assoc-in [agnt :name] (:memberName %2))))
                                      m members))
                            (into {} (for [agnt (keys @agnt-state)]
                                       [agnt (into {} (for [q qs] [q "loggedoff"]))]))
                            q-status)}))

(defn- make-event [target-type target event-type & {:as data}]
  (merge {:type event-type, target-type target} data))

(defn- agnt-event [agnt type & data] (apply make-event :agent agnt type data))

(defn- member-event [agnt q status]
  (when status
    (make-event :agent agnt "queueMemberStatus" :queue q :status status)))

(defn- name-event [agnt name] (when name (make-event :agent agnt "agentName" :name name)))

(defn- call-event [agnt unique-id phone-num & [name]]
  (agnt-event (digits agnt) "phoneNumber" :unique-id unique-id :number phone-num :name name))

(defn- qcount-event [q cnt] (make-event :queue q "queueCount" :count cnt))

(declare ^clojure.lang.IFn publish)

(defn- pbxis-event-filter [e]
  (let [agnt (e :agent), q (e :queue)]
    (locking lock
      (condp = (:type e)
        "callsInProgress"
        (let [curr-calls (@agnt-calls agnt)]
          (doseq [unique-id (keys (apply dissoc curr-calls (keys (e :calls))))]
            (publish (call-event agnt unique-id nil)))
          (doseq [[unique-id phone-num] (apply dissoc (e :calls) (keys curr-calls))]
            (publish (call-event agnt unique-id phone-num)))
          nil)
        "phoneNumber"
        (let [{:keys [unique-id number name]} e
              forget (fn [] (swap! agnt-calls
                                   #(do (cancel-schedule (>?> % agnt unique-id :forgetter))
                                        (dissoc-in % [agnt unique-id]))))
              e (if number
                  (do (when (@agnt-state agnt)
                        (swap! agnt-calls assoc-in [agnt unique-id]
                               {:number number, :name name
                                :forgetter (apply schedule forget FORGET-PHONENUM-DELAY)}))
                      e)
                  (do (forget) (assoc e :number (-?> (@agnt-calls agnt) first val :number))))]
          (when (replace-in-agnt-state agnt [:phone-number] (String. (or (e :number) ""))) e))
        "extensionStatus"
        (when (replace-in-agnt-state agnt [:exten-status] (String. (e :status))) e)
        "agentName"
        (when (replace-in-agnt-state agnt [:name] (String. (e :name))) e)
        "queueMemberStatus"
        (when (replace-in-agnt-state agnt [:member-status (e :queue)] (String. (e :status))) e)
        "queueCount"
        (do (swap! q-cnt assoc q (e :count)) e)
        e))))

(defn- new-event-hub []
  (let [ch (m/channel* :grounded? true :permanent? true :description "Event hub")]
    (m/splice ch
              (m/siphon->> (m/map* pbxis-event-filter)
                           (m/filter* #(when-not (nil? %) (spy "PBXIS event" %))) ch))))

(defn- enq [ch event] (when event (m/enqueue ch event)))

(defn- publish [event] (enq @event-hub event))

(defn incref [agnts]
  (swap! agnt-state (fn [st] (reduce (fn [st agnt] (update-in st [agnt :refcount] #(inc (or % 0))))
                                     st agnts))))

(defn decref [agnts]
  (swap! agnt-state (fn [st] (reduce (fn [st agnt] (let [curr (:refcount (st agnt) 0)]
                                          (if (<= curr 1)
                                            (dissoc st agnt)
                                            (assoc-in st [agnt :refcount] (dec curr)))))
                                     st agnts))))

(defn- send-introductory-events [ch agnts qs]
  (locking lock
    (let [agnt-state @agnt-state, q-cnt @q-cnt, agnt-calls @agnt-calls, q-set (set qs)]
      (doseq [agnt agnts, :let [state (agnt-state agnt)
                                calls (spy "agnt-calls" (agnt-calls agnt))
                                member-status (:member-status state)]]
        (enq ch (name-event agnt (state :name)))
        (doseq [[q status] (select-keys member-status qs)]
          (enq ch (member-event agnt q status)))
        (doseq [q (apply disj q-set (keys member-status))
                :let [{:keys [name status]} (agnt-q-status agnt q)]]
          (publish (name-event agnt name))
          (publish (member-event agnt q status)))
        (if calls
          (when-let [active-call (first calls)]
            (enq ch (call-event agnt (key active-call) (-> active-call val :number)
                                (-> active-call val :name))))
          (publish (agnt-event agnt "callsInProgress" :calls (calls-in-progress agnt))))
        (enq ch (agnt-event agnt "extensionStatus" :status
                                  (or (:exten-status state) (exten-status agnt)))))
      (doseq [[q cnt] (select-keys q-cnt qs)]
        (publish (make-event :queue q "queueCount" :count cnt)))
      (doseq [q (apply disj q-set (keys q-cnt))
              :let [{:keys [name calls]} (agnt-q-status "none" q)], :when calls]
        (publish (qcount-event q calls))))))

(defn event-channel
  "Sets up and returns a permanent lamina channel that will emit
   events related to the supplied agents and queues.

   agnts: a collection of agents' phone extension numbers.
   qs: a collection of agent queue names."
  [agnts qs]
  (loginfo "event-channel" agnts qs)
  (let [q-set (set qs)
        agnt-set (set agnts)
        eventch (m/permanent-channel)
        propagate-close #(close-permanent eventch)]
    (m/on-closed @event-hub propagate-close)
    (m/on-closed eventch #(do (m/cancel-callback @event-hub propagate-close)
                              (decref agnts)))
    (incref agnts)
    (m/siphon (m/filter*
               #(let [{:keys [agent queue]} %]
                  (and (or (nil? agent) (agnt-set agent))
                       (or (nil? queue) (q-set queue))))
               @event-hub)
              eventch)
    (send-introductory-events eventch agnts qs)
    eventch))

(def ^:private ignored-events
  #{"VarSet" "NewState" "NewChannel" "NewExten" "NewCallerId" "NewAccountCode" "ChannelUpdate"
    "RtcpSent" "RtcpReceived" "PeerStatus"})

(def ^:private activating-eventfilter (atom nil))

(defn- ensure-ami-event-filter [event t]
  (if (ignored-events t)
    (when-not (-?>> event :privilege (re-find #"agent|call"))
      (let [tru (Object.)
            activating (swap! activating-eventfilter #(or % tru))]
        (when (= activating tru)
          (ex/task (try
                     (send-action
                      (spy (<< "Received unfiltered event ~{t}, privilege ~(event :privilege).")
                           "Sending" (action "Events" {:eventMask "agent,call"})))
                     (finally (reset! activating-eventfilter nil)))))))
    (logdebug 'ami-event "AMI event\n" t (dissoc event :event-type :privilege))))

(defn- refresh-all-state []
  (let [{:keys [agnt-q-status q-cnt]} (full-agnt-q-status)
        calls-in-progress (calls-in-progress)]
    (doseq [[agnt q-status] agnt-q-status, [k v] q-status]
      (publish (if (= k :name) (name-event agnt v) (member-event agnt k v))))
    (doseq [[q cnt] q-cnt] (publish (qcount-event q cnt)))
    (doseq [[agnt calls] calls-in-progress]
      (publish (agnt-event agnt "callsInProgress" calls)))))

(defn- handle-ami-event [event]
  (let [t (:event-type event), unique-id (:uniqueId event)]
    (ensure-ami-event-filter event t)
    (condp re-matches t
      #"Connect"
      (ex/task (refresh-all-state))
      #"Join|Leave"
      (publish (qcount-event (:queue event) (:count event)))
      #"Dial"
      (when (= (event :subEvent) "Begin")
        (publish (call-event (event :channel) (event :srcUniqueId) (-> event :dialString digits)))
        (publish (call-event (event :destination) (event :destUniqueId)
                             (or (recall (event :srcUniqueId)) (event :callerIdNum))
                             (event :callerIdName))))
      #"Hangup"
      (publish (call-event (event :channel) unique-id nil))
      #"AgentCalled"
      (publish (call-event (event :agentCalled) unique-id (event :callerIdNum)))
      #"AgentComplete"
      (publish (agnt-event
                (event :member) "agentComplete"
                :uniqueId unique-id :talkTime (event :talkTime) :holdTime (event :holdTime)
                :recording (-?> event :variables (.get "FILEPATH"))))
      #"OriginateResponse"
      (let [action-id (event :actionId)]
        (if (= (event :response) "Success")
          (remember unique-id (recall action-id) DUE-EVENT-WAIT-SECONDS)
          (publish (agnt-event (event :exten) "originateFailed" :actionId action-id))))
      #"ExtensionStatus"
      (publish (agnt-event (digits (event :exten)) "extensionStatus"
                           :status (int->exten-status (event :status))))
      #"QueueMemberStatus"
      (let [agnt (event :location)]
        (publish (member-event (digits agnt) (event :queue)
                               (event->member-status event)))
        (publish (name-event agnt (event :memberName))))
      #"QueueMember(Add|Remov)ed" :>>
      #(publish (member-event (digits (event :location)) (event :queue)
                              (if (= (% 1) "Add") (event->member-status event) "loggedoff")))
      #"QueueMemberPaused"
      (let [agnt (digits (event :location)), q (event :queue)
            pub #(publish (member-event agnt q %))]
        (if (event :paused)
          (pub "paused")
          (ex/task (pub (>?> (agnt-q-status agnt q) :status)))))
      nil)))

(defn- actionid [] (<< "pbxis-~(.substring (-> (java.util.UUID/randomUUID) .toString) 0 8)"))

(defn originate-call
  "Issues a request to originate a call to the supplied phone number
   and patch it through to the supplied agent's extension.

   Returns the ID (string) of the request. The function returns
   immediately and doesn't wait for the call to be established. In the
   case of failure, an event will be received by the client referring
   to this ID.

In the case of a successful call, only a an phoneNumber event will be
received."
  [agnt phone]
  (let [actionid (actionid)
        context (@config :originate-context)
        tmout (originate-timeout)]
    (when (send-action (action "Originate"
                               {:context context
                                :exten agnt
                                :channel (str "Local/" phone "@" context)
                                :actionId actionid
                                :priority (int 1)
                                :async true})
                       tmout)
      (remember actionid phone (+ tmout DUE-EVENT-WAIT-SECONDS))
      actionid)))

(defn queue-action
"Executes an action against an agent's queue. This is a thin wrapper
  around an actual AMI QueueXxxAction.

type: action type, one of #{\"add\", \"pause\", \"remove\"}.
agnt: the agent on whose behalf the action is executed.
params: a map of action parameters:
   \"queue\": the queue name.
   \"memberName\": full name of the agent, to be associated with this
                   agent in this queue.
   \"paused\": the requested new paused-state of the agent.

The \"queue\" param applies to all actions; the \"paused\" param
applies to all except \"remove\", and \"memberName\" applies only to
\"add\"."
[type agnt params]
  (send-action (action (<< "Queue~(upcase-first type)")
                       (assoc params :interface (agnt->location agnt)))))

(def ami-listener
  (reify ManagerEventListener
    (onManagerEvent [_ event] (handle-ami-event (event-bean event)))))

(defn ami-connect
  "Connects to the AMI back-end and performs all other initialization
   tasks.

   host: host name or IP address of the Asterisk server.
   username, password: AMI username/password
   cfg: a map of configuration parameters---

     :location-prefix -- string to prepend to agent's extension
       in order to form the agent \"location\" as known to
       Asterisk. This is typically \"SCCP/\", \"SIT/\" or similar.

     :originate-context -- dialplan context used for call origination.
       Typically the default context is called \"default\".

     :originate-timeout-seconds -- time to wait for the called party to
       answer the phone."
  [host username password cfg]
  (locking ami-connection
    (reset! config (spy "PBXIS config"
                        (merge default-config
                               (select-keys cfg [:location-prefix :originate-context
                                                 :originate-timeout-seconds]))))
    (reset! event-hub (new-event-hub))
    (let [c (reset! ami-connection
                    (-> (ManagerConnectionFactory. host username password)
                        .createManagerConnection))]
      (doto c (.addEventListener ami-listener) .login))))

(defn ami-disconnect
  "Disconnects from the AMI and releases all resources. Publishes a PBXIS event
of type \"closed\" before closing the event hub."
  []
  (locking ami-connection
    (when-let [c @ami-connection] (reset! ami-connection nil) (.logoff c))
    (publish {:type "closed"})
    (close-permanent @event-hub)
    (reset! event-hub nil)
    (doseq [a [q-cnt agnt-state agnt-calls memory]] (reset! a {}))))
