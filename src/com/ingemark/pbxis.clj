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
  (require [com.ingemark.pbxis.util :as u :refer [enq >?>]]
    (lamina [core :as m] [executor :as ex])
    [clojure.set :as set] [com.ingemark.logging :refer :all] [clojure.walk :as walk]
    (clojure.core [incubator :refer (-?> -?>> dissoc-in)] [strint :refer (<<)]))
  (import (org.asteriskjava.manager ManagerConnectionFactory ManagerEventListener)
    java.util.concurrent.TimeUnit))

(def default-config {:location-prefix "SIP/"
                     :originate-context "default"
                     :redirect-context "default"})

(def FORGET-PHONENUM-DELAY [3 TimeUnit/HOURS])
(def ^:const DUE-EVENT-WAIT-SECONDS 5)
(def ^:const ORIGINATE-CALL-TIMEOUT-SECONDS 180)

(defonce config (atom {}))

(defonce ^:private agnt-state (atom {}))

(defonce ^:private q-cnt (atom {}))

(defonce ^:private lock (Object.))

(defonce ^:private ami-connection (atom nil))

(defonce ^:private event-hub (atom nil))

(defonce ^:private agnt-calls (atom {}))

(defn- send-action [a & [timeout]]
  (spy 'ami-event "Action response"
       (->> (-> (if timeout
                  (.sendAction @ami-connection a timeout)
                  (.sendAction @ami-connection a))
                bean (dissoc :attributes :class :dateReceived))
            (remove #(nil? (val %)))
            (into (sorted-map)))))

(defn- send-eventaction [a]
  (->>
   (doto (.sendEventGeneratingAction @ami-connection a)
     (-> .getResponse (#(logdebug 'ami-event "Manager response"
                                  (.getResponse %) "|" (.getMessage %)))))
   .getEvents
   (mapv u/event-bean)))

(defn- agnt->location [agnt] (when agnt (str (@config :location-prefix) agnt)))

(defn originate-call
  "Issues a request to originate a call to the supplied phone number
   and patch it through to the supplied agent's extension.

   If a :caller-id named argument is provided, use it as the callerId
   parameter on the Originate action.

   If a :variables named argument is provided, its value must be a map.
   The map's keys will be stringified and the result will be used as
   the variables parameter on the Originate action.

   Returns the ID (string) of the request. The function returns
   immediately and doesn't wait for the call to be established. In the
   case of failure, an event will be received by the client referring
   to this ID.

   In the case of a successful call, only a phoneNumber event will be
   received."
  [agnt phone & {:keys [caller-id variables]}]
  (loginfo (<< "(originate-call \"~{agnt}\" \"~{phone}\" \"~{caller-id}\")"))
  (let [actionid (u/actionid)]
    (when (send-action (u/action "Originate"
                                 {:exten phone
                                  :channel (agnt->location agnt)
                                  :callerId (str caller-id)
                                  :actionId actionid
                                  :priority (int 1)
                                  :context (@config :originate-context)
                                  :variables (into {} (walk/stringify-keys variables))
                                  :async true}))
      (u/remember actionid phone ORIGINATE-CALL-TIMEOUT-SECONDS)
      actionid)))

(defn find-channels [agnt-or-chan]
  (let [status-result (send-eventaction (u/action "Status" {}))
        the-result (some #(when (= (:channel %) agnt-or-chan) %) status-result)
        candidate-channels (filter #(= (u/channel-name->exten (% :bridgedChannel)) agnt-or-chan)
                             status-result)
        mapper #(-> {:agentChannel (:bridgedChannel %)
                     :bridgedChannel (:channel %)
                     :callerIdNum (:callerIdNum %)})]
    (if the-result
      [(mapper the-result)]
      (map mapper candidate-channels))))

(defn redirect-call
  "Redirects (transfers) a call to another extension.
  First argument is either the raw name of the channel which to
  redirect to the new destination, or an agent's extension number.
  In the latter case a channel belonging to the agent will be
  looked up and its bridged channel will be the one that gets
  redirected. If there are several channels belonging to the agent,
  a map of the shape
  {:candidates [{:agentChannel, :bridgedChannel, :callerIdNum}, ...]}
  will be returned, listing all the agent's channels, their bridged
  channels, and the associated caller IDs of the remote parties. This
  can then be used to call this function again with the chosen
  channel name.

  Note: the type of all parameters is string."
  [agnt-or-chan destination]
  (let [candidates (find-channels agnt-or-chan)]
    (cond
      (not (first candidates)) nil
      (next candidates) {:candidates candidates}
      :else (send-action (u/action "Redirect"
                           {:channel (-> candidates first :bridgedChannel)
                            :exten destination
                            :context (@config :redirect-context)
                            :priority (int 1)})))))

(defn park-and-announce
  "Parks a call so it can be retrieved by calling another
  extension number. The number is announced in voice on the
  channel that was bridged to the parked channel. If the call is
  not retrieved within ORIGINATE-CALL-TIMEOUT-SECONDS, it is
  returned to the owner of the bridged channel. The supplied
  argument is either the raw name of the channel to park, or an
  agent's extension number (a string in both cases). In the latter
  case a channel belonging to the agent will be looked up and its
  bridged channel is the one that gets parked. If there are several
  channels belonging to the agent, a map of the shape
  {:candidates [{:agentChannel, :bridgedChannel, :callerIdNum}, ...]}
  will be returned, listing all the agent's channels, their bridged
  channels, and the associated caller IDs of the remote parties. This
  can then be used to call this function again with the chosen
  channel name."
  [agnt-or-chan]
  (let [candidates (find-channels agnt-or-chan)]
    (cond
      (not (first candidates)) nil
      (next candidates) {:candidates candidates}
      :else (let [c (first candidates)]
              (send-action (u/action "Park"
                             {:channel (c :bridgedChannel)
                              :channel2 (c :agentChannel)
                              :timeout (int (* 1000 ORIGINATE-CALL-TIMEOUT-SECONDS))})
                5000)))))

(defn queue-action
  "Executes an action against a queue. This is a thin wrapper
   around an actual AMI QueueXxxAction.

   type: action type, for example :add, :pause, :remove.
   params: a map of action parameters:
     :agent        the agent on whose behalf the action is executed.
     :queue        the queue name.
     :memberName   full name of the agent, to be associated with this
                   agent in this queue.
     :paused       the requested new paused-state of the agent.

   The :queue param applies to all actions; the :paused param
   applies to all except \"remove\", and :member-name applies only to
   \"add\"."
  [type params]
  (let [agnt (:agent params)
        params (if agnt (-> params (assoc :interface (agnt->location agnt)) (dissoc :agent))
                   params)]
    (send-action (u/action (<< "Queue~(u/upcamelize type)") params))))

(defn- q-status [q agnt]
  (->> (send-eventaction (u/action "QueueStatus" {:member (agnt->location agnt) :queue q}))
       (reduce (fn [vect ev]
                 (let [type (ev :event-type), ev (dissoc ev :event-type)]
                   (condp = type
                     "QueueParams" (conj vect [ev])
                     "QueueMember" (conj (pop vect) (conj (peek vect) ev))
                     vect)))
               [])))

(defn queue-status [q]
  (vec
   (for [[param-ev & member-evs] (q-status q nil)]
     (-> param-ev
         (dissoc :actionId :serviceLevelPerf :serviceLevel :strategy :weight :privilege)
         (assoc :members
           (vec (for [e member-evs]
                  (-> e
                      (dissoc :actionId :privilege :dynamic :static :membership :name :queue)
                      (assoc :status (u/event->member-status e))
                      (assoc :agent (u/digits (:location e)))))))))))

(defn- agnt-q-status [agnt q]
  (let [[q-event member-event] (first (q-status q agnt))]
    (when q-event {:calls (:calls q-event), :status (u/event->member-status member-event)
                   :name (:memberName member-event)})))

(defn- calls-in-progress [& [agnt]]
  (let [r (reduce
           #(apply assoc-in %1 %2)
           {}
           (for [{:keys [bridgedChannel bridgedUniqueId callerId event-type]}
                 (send-eventaction (u/action "Status" {}))
                 :let [ag (u/digits bridgedChannel)]
                 :when (and bridgedUniqueId (if agnt (= ag agnt) true))]
             [[ag bridgedUniqueId] (or callerId "")]))]
    (if agnt (r agnt) r)))

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

(defn- exten-status [agnt]
  (let [r (-> (u/action "ExtensionState" {:exten agnt :context "hint"})
              send-action)]
    (when (= (r :response) "Success") (-> r :status u/int->exten-status))))

(defn- send-introductory-events [ch agnts qs]
  (locking lock
    (let [agnt-state @agnt-state, q-cnt @q-cnt, agnt-calls @agnt-calls, q-set (set qs)]
      (doseq [agnt agnts, :let [state (agnt-state agnt)
                                calls (agnt-calls agnt)
                                member-status (:member-status state)]]
        (enq ch (u/name-event agnt (:name state)))
        (doseq [[q status] (select-keys member-status qs)]
          (enq ch (u/member-event agnt q status)))
        (doseq [q (apply disj q-set (keys member-status))
                :let [{:keys [name status]} (agnt-q-status agnt q)]]
          (publish (u/name-event agnt name))
          (publish (u/member-event agnt q status)))
        (if calls
          (when-let [active-call (first calls)]
            (enq ch (u/call-event agnt (key active-call) (-> active-call val :number)
                                  (-> active-call val :name))))
          (publish (u/agnt-event agnt "callsInProgress" :calls (calls-in-progress agnt))))
        (enq ch (u/agnt-event agnt "extensionStatus" :status
                              (or (:exten-status state) (exten-status agnt)))))
      (doseq [[q cnt] (select-keys q-cnt qs)] (publish (u/qcount-event q cnt)))
      (doseq [q (apply disj q-set (keys q-cnt))
              :let [{:keys [name calls]} (agnt-q-status "none" q)], :when calls]
        (publish (u/qcount-event q calls))))))

(defn event-channel
  "agnts: a collection of agents' phone extension numbers.
   qs: a collection of agent queue names.

   Sets up and returns a lamina channel that will emit events related
   to the supplied agents and queues. Depending on its type, an event
   may have the properties :agent and/or :queue, called the \"scope
   properties\". An event channel will receive an event iff the value
   of each scope property of the event exists in the corresponding
   list in the configuration of the event channel. So an event with
   neither property is received by all channels and an event with both
   properties is received only by channels that include both the agent
   and the queue in their configuration.
"
  [agnts qs]
  (loginfo (<< "(event-channel ~{agnts} ~{qs})"))
  (let [q-set (set qs), agnt-set (set agnts), eventch (m/channel)]
    (m/on-closed eventch #(decref agnts))
    (incref agnts)
    (u/leech (m/filter*
              #(let [{:keys [agent queue]} %]
                 (and (or (nil? agent) (agnt-set agent))
                      (or (nil? queue) (q-set queue))))
              @event-hub)
             eventch)
    (send-introductory-events eventch agnts qs)
    eventch))

(let [ignored-events
      #{"VarSet" "NewState" "NewChannel" "NewExten" "NewCallerId" "NewAccountCode"
        "ChannelUpdate" "RtcpSent" "RtcpReceived" "PeerStatus"}
      activating-eventfilter (atom nil)]

  (defn- ensure-ami-event-filter [ami-ev]
    (let [t (:event-type ami-ev)]
      (if (ignored-events t)
        (when-not (-?>> ami-ev :privilege (re-find #"agent|call"))
          (let [tru (Object.), activating (swap! activating-eventfilter #(or % tru))]
            (when (= activating tru)
              (ex/task
               (try (send-action
                     (spy (<< "Received unfiltered ami-ev ~{t}, privilege ~(ami-ev :privilege).")
                          "Sending" (u/action "Events" {:eventMask "agent,call"})))
                    (finally (reset! activating-eventfilter nil)))))))
        (logdebug 'ami-event "AMI event\n" t (dissoc ami-ev :event-type :privilege))))))

(defn- new-ami-channel []
  (let [ch (m/channel)
        amich (m/splice ch (m/join->> (m/map* u/event-bean) ch))]
    (m/receive-all amich ensure-ami-event-filter)
    amich))

(defn- full-agnt-q-status []
  (loginfo "Fetching full Asterisk queue status")
  (let [agnts (keys @agnt-state) qs (keys @q-cnt), q-status (mapcat #(q-status % nil) qs)]
    {:q-cnt (into {} (for [[q-st] q-status] [(:queue q-st) (:calls q-st)]))
     :agnt-q-status (reduce (fn [m [q-st & members]]
                              (reduce #(let [agnt (u/digits (:location %2))]
                                         (-> %1
                                             (assoc-in [agnt (:queue q-st)]
                                                       (u/event->member-status %2))
                                             (assoc-in [agnt :name] (:memberName %2))))
                                      m members))
                            (into {} (for [agnt (keys @agnt-state)]
                                       [agnt (into {} (for [q qs] [q "loggedoff"]))]))
                            q-status)}))

(defn- refresh-all-state []
  (let [{:keys [agnt-q-status q-cnt]} (full-agnt-q-status)
        calls-in-progress (calls-in-progress)]
    (for [[agnt q-status] agnt-q-status, [k v] q-status]
      (if (= k :name) (u/name-event agnt v) (u/member-event agnt k v)))
    (for [[q cnt] q-cnt] (u/qcount-event q cnt))
    (for [[agnt calls] calls-in-progress] (u/agnt-event agnt "callsInProgress" :calls calls))))

(defn- ami->pbxis [ami-ev]
  (let [t (:event-type ami-ev), unique-id (:uniqueId ami-ev)]
    (condp re-matches t
      #"Connect"
      (ex/task (refresh-all-state))
      #"Join|Leave"
      (u/qcount-event (:queue ami-ev) (:count ami-ev))
      #"Dial"
      (when (= (ami-ev :subEvent) "Begin")
        [(let [amich (ami-ev :channel)]
           (when-not (.startsWith amich "Local")
             (u/call-event amich (ami-ev :srcUniqueId) (-> ami-ev :dialString u/digits))))
         (u/call-event (ami-ev :destination) (ami-ev :destUniqueId)
                       (or (u/recall (ami-ev :srcUniqueId)) (ami-ev :callerIdNum))
                       (ami-ev :callerIdName))])
      #"Hangup"
      (u/call-event (ami-ev :channel) unique-id nil)
      #"AgentRingNoAnswer"
      (u/call-event (ami-ev :member) unique-id nil)
      #"QueueCallerAbandon"
      (when-let [agnt (some #(when ((val %) unique-id) (key %)) @agnt-calls)]
        (u/call-event agnt unique-id nil))
      #"AgentCalled"
      (u/call-event (ami-ev :agentCalled) unique-id
                    (ami-ev :callerIdNum) (ami-ev :callerIdName))
      #"AgentComplete"
      (let [agnt (u/digits (ami-ev :member))]
        [(u/call-event agnt unique-id nil)
         (u/agnt-event
          agnt "agentComplete"
          :uniqueId unique-id :talkTime (ami-ev :talkTime) :holdTime (ami-ev :holdTime)
          :recording (-?> ami-ev :variables (.get "FILEPATH")))])
      #"OriginateResponse"
      (let [action-id (ami-ev :actionId)]
        (if (= (ami-ev :response) "Success")
          (u/remember unique-id (u/recall action-id) DUE-EVENT-WAIT-SECONDS)
          (u/agnt-event (ami-ev :exten) "originateFailed" :actionId action-id)))
      #"ExtensionStatus"
      (u/agnt-event (u/digits (ami-ev :exten)) "extensionStatus"
                    :status (u/int->exten-status (ami-ev :status)))
      #"QueueMemberStatus"
      (let [agnt (ami-ev :location)]
        [(u/member-event (u/digits agnt) (ami-ev :queue)
                         (u/event->member-status ami-ev))
         (u/name-event agnt (ami-ev :memberName))])
      #"QueueMember(Add|Remov)ed" :>>
      #(let [agnt (ami-ev :location)]
         [(u/member-event (u/digits agnt) (ami-ev :queue)
                          (if (= (% 1) "Add") (u/event->member-status ami-ev) "loggedoff"))
          (u/name-event agnt (ami-ev :memberName))])
      #"QueueMemberPaused"
      (let [agnt (u/digits (ami-ev :location)), q (ami-ev :queue)
            member-ev #(u/member-event agnt q %)]
        (if (ami-ev :paused)
          (member-ev "paused")
          (ex/task (member-ev (>?> (agnt-q-status agnt q) :status)))))
      nil)))

(defn- update-agnt-state [agnt f & args]
  (swap! agnt-state #(if (% agnt)
                       (apply update-in % [agnt] f args)
                       %)))

(defn- replace-in-agnt-state [agnt path new-val]
  (let [swapped-in (update-agnt-state agnt update-in path #(if (= % new-val) % new-val))]
    (identical? new-val (apply >?> swapped-in agnt path))))

(defn- pbxis-event-filter [e]
  (let [agnt (e :agent), q (e :queue)]
    (locking lock
      (condp = (:type e)
        "callsInProgress"
        (let [curr-calls (@agnt-calls agnt)]
          (doseq [unique-id (keys (apply dissoc curr-calls (keys (e :calls))))]
            (publish (u/call-event agnt unique-id nil)))
          (doseq [[unique-id phone-num] (apply dissoc (e :calls) (keys curr-calls))]
            (publish (u/call-event agnt unique-id phone-num)))
          nil)
        "phoneNumber"
        (let [{:keys [unique-id number name]} e
              forget (fn [] (swap! agnt-calls
                                   #(do (u/cancel-schedule (>?> % agnt unique-id :forgetter))
                                        (dissoc-in % [agnt unique-id]))))
              e (if number
                  (do (when (@agnt-state agnt)
                        (swap! agnt-calls assoc-in [agnt unique-id]
                               {:number number, :name name
                                :forgetter (apply u/schedule forget FORGET-PHONENUM-DELAY)}))
                      e)
                  (do (forget)
                      (merge e (select-keys (-?> (@agnt-calls agnt) first val) [:number :name]))))]
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

(defn- new-event-hub [amich]
  (let [ch (m/channel)]
    (m/join (->> amich
                 ((u/pipelined m/map*) ami->pbxis)
                 (m/map* #(if (sequential? %) % [%]))
                 m/concat*
                 (m/remove* nil?))
            ch)
    (doto (m/splice (->> ch (m/map* pbxis-event-filter) (m/remove* nil?)) ch)
      (m/receive-all #(logdebug "PBXIS event" %)))))

(defn ami-disconnect
  "Disconnects from the AMI and releases all resources. Publishes a PBXIS event
   of type \"closed\" before closing the event hub."
  [amich]
  (locking lock
    (when @ami-connection (.logoff @ami-connection) (reset! ami-connection nil))
    (publish {:type "closed"})
    (m/close amich)
    (reset! event-hub nil)
    (doseq [a [q-cnt agnt-state agnt-calls u/memory]] (reset! a {}))))

(defn ami-connect
  "Connects to the AMI back-end and performs all other initialization
   tasks.

   host: host name or IP address of the Asterisk server.
   username, password: AMI username/password
   cfg: a map of configuration parameters---

     :location-prefix -- string to prepend to agent's extension
       in order to form the agent \"location\" as known to
       Asterisk. This is typically \"SCCP/\", \"SIT/\" or similar.

     :originate-context -- the value to use for the context attribute on the
       AMI Originate action. Default is \"default\".

     :redirect-context -- the value to use for the context attribute on the
       AMI Redirect action. Default is \"default\".
"
  [host username password cfg]
  (locking lock
    (reset! config (spy "PBXIS config"
                        (merge default-config
                          (select-keys cfg [:location-prefix
                                            :originate-context
                                            :redirect-context]))))
    (let [amich (new-ami-channel)
          amiconn (reset! ami-connection
                          (-> (ManagerConnectionFactory. host username password)
                              .createManagerConnection))]
      (reset! event-hub (new-event-hub amich))
      (doto amiconn
        (.addEventListener (reify ManagerEventListener
                             (onManagerEvent [_ event] (m/enqueue amich event))))
        .login)
      #(ami-disconnect amich))))
