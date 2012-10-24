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
  (require [clojure.set :as set] [clojure.data :as d]
           [lamina.core :as m] [lamina.core.graph.node :as node] [lamina.core.channel :as chan]
           [com.ingemark.logging :refer :all]
           (clojure.core [incubator :refer (-?> -?>> dissoc-in)] [strint :refer (<<)]))
  (import (org.asteriskjava.manager ManagerConnectionFactory ManagerEventListener)
          org.asteriskjava.manager.event.ManagerEvent
          (java.util.concurrent Executors TimeUnit TimeoutException)
          (clojure.lang Reflector RT)))

(defonce config (atom {:channel-prefix "SCCP/"
                       :originate-context "default"
                       :originate-timeout-seconds 45
                       :poll-timeout-seconds 30
                       :unsub-delay-seconds 15
                       :agent-gc-delay-minutes 60}))

(defn- poll-timeout [] (* 1000 (@config :poll-timeout-seconds)))
(defn- unsub-delay [] [(@config :unsub-delay-seconds) TimeUnit/SECONDS])
(defn- agnt-gc-delay [] [(@config :agent-gc-delay-minutes) TimeUnit/MINUTES])
(defn- originate-timeout [] (@config :originate-timeout-seconds))
(def EVENT-BURST-MILLIS 100)
(def DUE-EVENT-WAIT-SECONDS 5)

(defonce ^:private lock (Object.))

(defonce ami-connection (atom nil))

(defonce ^:private amiq-cnt-agnts (atom {}))

(defonce ^:private agnt-state (atom {}))

(defonce ^:private ticket-agnt (atom {}))

(defonce ^:private agnt-unsubscriber (atom {}))

(defonce ^:private agnt-gc (atom {}))

(defonce ^:private memory (atom {}))

(defn- >?> [& fs] (reduce #(when %1 (%1 %2)) fs))

(defn- invoke [^String m o & args]
  (clojure.lang.Reflector/invokeInstanceMethod o m (into-array Object args)))

(defn- upcase-first [s]
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
  (let [r (spy "Action response"
               (->> (-> (if timeout
                          (.sendAction @ami-connection a timeout)
                          (.sendAction @ami-connection a))
                        bean (dissoc :attributes :class :dateReceived))
                    (remove #(nil? (val %)))
                    (into (sorted-map))))]
    (when (= (r :response) "Success") r)))

(defn- send-eventaction [a]
  (->>
   (doto (.sendEventGeneratingAction @ami-connection a) (-> .getResponse logdebug))
   .getEvents
   (mapv event-bean)))

(defn- schedule [task & [delay unit]]
  (.schedule lamina.core.threads/scheduled-executor task (or delay 0) (or unit TimeUnit/SECONDS)))

(defn- remember [k v timeout]
  (swap! memory assoc k v)
  (schedule #(swap! memory dissoc k) timeout))

(defn- recall [k] (when-let [v (@memory k)] (swap! memory dissoc k) v))

(defn- agnt->location [agnt] (when agnt (str (@config :channel-prefix) agnt)))

(defn- update-agnt-state [agnt f & args] (swap! agnt-state #(if (% agnt)
                                                              (apply update-in % [agnt] f args)
                                                              %)))

(defn- amiq-status [amiq agnt]
  (->> (send-eventaction (action "QueueStatus" {:member (agnt->location agnt) :queue amiq}))
       (reduce (fn [vect ev] (condp = (ev :event-type)
                               "QueueParams" (conj vect [ev])
                               "QueueMember" (conj (pop vect) (conj (peek vect) ev))
                               vect))
               [])))

(defn- update-amiq-cnt-agnts [amiq-cnt-agnts agnt amiqs]
  (let [add #(if %1 (update-in %1 [:agnts] conj %2) {:agnts #{%2}})
        amiqs-for-add (into #{} amiqs)
        amiqs-for-remove (set/difference (into #{} (keys amiq-cnt-agnts)) amiqs-for-add)
        amiq-cnt-agnts (reduce #(update-in %1 [%2] add agnt) amiq-cnt-agnts amiqs-for-add)
        amiq-cnt-agnts (reduce #(if (= (>?> %1 %2 :agnts) #{agnt})
                                  (dissoc %1 %2) (update-in %1 [%2 :agnts] disj agnt))
                               amiq-cnt-agnts amiqs-for-remove)]
    (reduce (fn [amiq-cnt-agnts amiq]
              (update-in amiq-cnt-agnts [amiq :cnt]
                         #(or % (-?> (amiq-status amiq "$none$") first first :calls))))
            amiq-cnt-agnts (keys amiq-cnt-agnts))))

(defn- set-schedule [task-atom agnt delay task]
  (let [newsched (when delay
                   (apply schedule #(locking lock (swap! task-atom dissoc agnt) (task)) delay))]
    (swap! task-atom update-in [agnt] #(do (-?> % (.cancel false)) newsched))))

(declare ^:private config-agnt)

(defn- reschedule-agnt-gc [agnt]
  (set-schedule agnt-gc agnt (agnt-gc-delay) #(config-agnt agnt [])))

(defn- set-agnt-unsubscriber-schedule [agnt reschedule?]
  (set-schedule agnt-unsubscriber agnt (when reschedule? (unsub-delay))
                #(locking lock
                   (loginfo "Unsubscribe agent" agnt)
                   (when-let [now-state (@agnt-state agnt)]
                     (swap! ticket-agnt dissoc (now-state :ticket))
                     (update-agnt-state agnt dissoc :ticket :eventch)))))

(defn- ticket [] (-> (java.util.UUID/randomUUID) .toString))

(def int->exten-status {0 "not_inuse" 1 "inuse" 2 "busy" 4 "unavailable" 8 "ringing" 16 "onhold"})

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

(defn- agnt-qs-status [agnt qs]
  (select-keys (into {} (for [[q-st member] (amiq-status nil agnt)]
                          [(:queue q-st) (event->member-status member)])) qs))

(defn- calls-in-progress [& [agnt]]
  (let [agnt-state @agnt-state
        r (reduce
           #(apply assoc-in %1 %2)
           {}
           (for [{:keys [bridgedChannel bridgedUniqueId callerId]}
                 (send-eventaction (action "Status" {}))
                 :let [ag (digits bridgedChannel)]
                 :when (if agnt (= ag agnt) (agnt-state ag))]
             [[agnt bridgedUniqueId] callerId]))]
    (if agnt (r agnt) r)))

(defn- full-update-agent-amiq-status []
  (loginfo "Refreshing Asterisk queue status")
  (locking lock
    (let [now-agnt-state @agnt-state
          now-amiq-cnt-agnts @amiq-cnt-agnts
          amiq-status (mapcat #(amiq-status % nil) (keys now-amiq-cnt-agnts))
          all-agnts-status (reduce (fn [m [q-st & members]]
                                     (reduce #(assoc-in %1 [(digits (:location %2)) (:queue q-st)]
                                                        (event->member-status %2))
                                             m members))
                                   {}
                                   amiq-status)
          all-qs-cnt (into {} (for [[q-st] amiq-status] [(:queue q-st) (:calls q-st)]))]
      (swap! amiq-cnt-agnts
             #(reduce (fn [amiq-cnt-agnts amiq]
                        (assoc-in amiq-cnt-agnts [amiq :cnt] (all-qs-cnt amiq))) % (keys %)))
      (swap! agnt-state
             (fn [agnt-state]
               (reduce (fn [agnt-state agnt]
                         (update-in agnt-state [agnt :amiq-status]
                                    #(merge (into {} (for [q (keys %)] [q "loggedoff"])))
                                    (all-agnts-status agnt)))
                       agnt-state
                       (keys agnt-state))))
      {:amiq-cnt (into {} (for [[amiq {:keys [cnt]}] now-amiq-cnt-agnts
                               :let [new-cnt (all-qs-cnt amiq)], :when (not= cnt new-cnt)]
                           [amiq new-cnt]))
       :agnt-amiqstatus
       (into {} (for [[agnt state] now-agnt-state
                      :let [upd (second (d/diff (state :amiq-status) (all-agnts-status agnt)))]
                      :when upd]
                  [agnt upd]))})))

(defn- update-one-agent-amiq-status [agnt q st]
  (locking lock
    (let [now-state (@agnt-state agnt)
          change? (and now-state (not= st (now-state :amiq-status q)))]
      (when change? (update-agnt-state agnt assoc-in [:amiq-status q] st))
      change?)))

(defn- replace-ticket [agnt old new]
  (swap! ticket-agnt #(-> % (dissoc old) (assoc new agnt)))
  (update-agnt-state agnt assoc :ticket new))

(defn- push-event [agnt k & vs]
  (let [agnt (digits agnt)]
    (when-let [ch (:eventch (@agnt-state agnt))]
      (locking ch (m/enqueue ch (spy "enqueue event" agnt (vec (cons k vs))))))))

(defn config-agnt
  "Subscribes an agent to the event stream. If the agent is already
subscribed, reconfigures the subscription.

agnt: agent's phone extension number.
qs: a collection of agent queue names. If empty or nil, the agent is
    unsubscribed.

Returns the ticket (string) to be passed to events-for or attach-sink. The
ticket will be valid for unsub-delay-seconds.

If unsubscribing, returns a string message \"Agent {agent} unsubscribed\"."
  [agnt qs]
  (loginfo "config-agnt" agnt qs)
  (locking lock
    (swap! amiq-cnt-agnts update-amiq-cnt-agnts agnt qs)
    (let [now-state (@agnt-state agnt)]
      (if (seq qs)
        (let [tkt (ticket), eventch (m/channel)]
          (if now-state
            (do (replace-ticket agnt (:ticket now-state) tkt)
                (update-agnt-state agnt assoc :eventch eventch :sink nil)
                (doto (-?> now-state :eventch)
                  (m/enqueue ["closed"])
                  m/close))
            (do (swap! ticket-agnt assoc tkt agnt)
                (swap! agnt-state assoc agnt
                       {:ticket tkt :eventch eventch :sink nil
                        :exten-status (exten-status agnt)
                        :calls (calls-in-progress agnt)})))
          (when (not= (set qs) (-?> now-state :amiq-status keys set))
            (update-agnt-state agnt assoc :amiq-status (agnt-qs-status agnt qs)))
          (let [st (@agnt-state agnt)]
            (when-not (st :sink)
              (set-agnt-unsubscriber-schedule agnt true)
              (reschedule-agnt-gc agnt))
            (push-event agnt "extensionStatus" (st :exten-status))
            (push-event agnt "queueMemberStatus" (st :amiq-status))
            (push-event agnt "phoneNumber" (-?> st :calls first val)))
          (push-event agnt "queueCount"
                      (into {} (for [[amiq {:keys [cnt]}] (select-keys @amiq-cnt-agnts qs)] [amiq cnt])))
          tkt)
        (do
          (swap! ticket-agnt dissoc (:ticket now-state))
          (swap! agnt-state dissoc agnt)
          (<< "Agent ~{agnt} unsubscribed"))))))

(defn attach-sink
  "Registers a sink (a unary function) for an agent's events.
Requires a ticket. Returns a new ticket.  The supplied ticket is
invalidated.

The very first call to the sink function will pass the
event [\"newTicket\" newtkt], where newtkt is the same ticket that is
returned by this function.

If the sink function throws an exception, it is automatically
deregistered and the unsubscribe countdown starts. If a new sink
is registered within unsub-delay-seconds, it will receive all the
events following the one whose handling threw the exception.

If this function is called while a call to events-for is in progress
for the same agent, the resulting behavior is unspecified."
  [tkt sink]
  (locking lock
    (when-let [agnt (@ticket-agnt tkt)]
      (when-let [ch (>?> @agnt-state agnt :eventch)]
        (set-schedule agnt-gc agnt nil nil)
        (let [newtkt (ticket)]
          (sink ["newTicket" newtkt])
          (update-agnt-state agnt update-in [:sink]
                             #(do (m/cancel-callback ch %) sink))
          (m/receive-all ch sink)
          (replace-ticket agnt tkt newtkt)
          (set-agnt-unsubscriber-schedule agnt false)
          newtkt)))))

(defn detach-sink
  "Deregisters the sink (if any) from the supplied agent's event
channel. The channel will continue to accumulate events for
another unsub-delay-seconds and if another event consumer is attached
within this period, no events will be lost.

Parameter: either agent ID or a valid ticket."
  [key]
  (locking lock
    (let [agnt (or (@ticket-agnt key) key)]
      (update-agnt-state agnt
                         (fn [st]
                           (update-in st [:sink]
                                      #(do (-?> (st :eventch) (m/cancel-callback %)) nil))))
      (reschedule-agnt-gc agnt)
      (set-agnt-unsubscriber-schedule agnt true)
      (<< "Deregistered event sink for agent ~{agnt}"))))

(defn events-for
  "Synchronously receives events associated with a ticket. The
function blocks until an event appears or the configured poll-timeout
elapses. The supplied ticket is immediately invalidated and the next
call must use the ticket returned by the current call. The ticket is
valid for unsub-delay-seconds. If the next call arrives within the
ticket's validity period, it will receive all events following the
last event received by the current call.

Returns a map {:ticket \"new-ticket\" :events [events]}.

If the events vector contains a \"closed\" event, that means
that config-agnt was called concurrently for the agent associated
with the current call to this function. In that case the ticket returned
by this call is already invalid."
  [tkt]
  (when-let [[agnt ch newtkt] (locking lock
                                (when-let [agnt (@ticket-agnt tkt)]
                                  (when-let [ch (>?> @agnt-state agnt :eventch)]
                                    (reschedule-agnt-gc agnt)
                                    (let [newtkt (ticket)]
                                      (replace-ticket agnt tkt newtkt)
                                      (set-agnt-unsubscriber-schedule agnt false)
                                      [agnt ch newtkt]))))]
    (try
      (let [evs (node/drain (chan/emitter-node ch))
            evs (if (seq evs)
                  evs
                  (try (let [ev @(m/with-timeout (poll-timeout) (m/read-channel ch))]
                         (Thread/sleep EVENT-BURST-MILLIS)
                         (conj (node/drain (chan/emitter-node ch)) ev))
                       (catch TimeoutException _ nil)))]
        (spy "Events for" agnt {:ticket newtkt :events (vec evs)}))
      (finally (when (= newtkt (>?> @agnt-state agnt :ticket))
                 (set-agnt-unsubscriber-schedule agnt true))))))

(defn- broadcast-qcount [ami-event]
  (locking lock
    (let [amiq (ami-event :queue), cnt (ami-event :count)]
      (when-let [cnt-agnts (@amiq-cnt-agnts amiq)]
        (swap! amiq-cnt-agnts assoc-in [amiq :cnt] cnt)
        (doseq [agnt (cnt-agnts :agnts)] (push-event agnt "queueCount" {amiq cnt}))))))

(def ^:private ignored-events
  #{"VarSet" "NewState" "NewChannel" "NewExten" "NewCallerId" "NewAccountCode" "ChannelUpdate"
    "RtcpSent" "RtcpReceived" "PeerStatus"})

(def ^:private activating-eventfilter (atom nil))

(defn- register-call [agnt id phone-num]
  (let [agnt (digits agnt)]
    (logdebug "Register call" agnt id phone-num)
    (update-agnt-state agnt #(if phone-num
                               (assoc-in % [:calls id] phone-num)
                               (dissoc-in % [:calls id])))
    (push-event agnt "phoneNumber" (or phone-num (-?> (@agnt-state agnt) :calls first val)))))

(defn- handle-ami-event [event]
  (let [t (:event-type event)]
    (if (ignored-events t)
      (when-not (-?>> event :privilege (re-find #"agent|call"))
        (let [tru (Object.)
              activating (swap! activating-eventfilter #(or % tru))]
          (when (= activating tru)
            (schedule #(try
                         (send-action
                          (spy (<< "Received unfiltered event ~{t}, privilege ~(event :privilege).")
                               "Sending" (action "Events" {:eventMask "agent,call"})))
                         (finally (reset! activating-eventfilter nil)))))))
      (logdebug 'ami-event "AMI event\n" t (dissoc event :event-type :privilege)))
    (let [unique-id (event :uniqueId)]
      (condp re-matches t
        #"Connect"
        (schedule #(let [{:keys [agnt-amiqstatus amiq-cnt]} (full-update-agent-amiq-status)
                         calls-in-progress (calls-in-progress)]
                     (doseq [[agnt upd] agnt-amiqstatus] (push-event agnt "queueMemberStatus" upd))
                     (doseq [[amiq cnt] amiq-cnt] (broadcast-qcount {:queue amiq :count cnt}))
                     (doseq [agnt (keys @agnt-state)]
                       (update-agnt-state agnt assoc-in [:calls] select-keys
                                          (keys (calls-in-progress agnt))))))
        #"Join|Leave"
        (broadcast-qcount event)
        #"Dial"
        (when (= (event :subEvent) "Begin")
          (register-call (event :channel) (event :srcUniqueId) (-> event :dialString digits))
          (register-call (event :destination) (event :destUniqueId)
                         (or (recall (event :srcUniqueId)) (event :callerIdNum))))
        #"Hangup"
        (register-call (event :channel) unique-id nil)
        #"AgentCalled"
        (register-call (event :agentCalled) unique-id (event :callerIdNum))
        #"AgentComplete"
        (push-event (event :member) "agentComplete"
                   unique-id (event :talkTime) (event :holdTime)
                   (-?> event :variables (.get "FILEPATH")))
        #"OriginateResponse"
        (let [action-id (event :actionId)]
          (if (= (event :response) "Success")
            (remember unique-id (recall action-id) DUE-EVENT-WAIT-SECONDS)
            (push-event (event :exten) "originateFailed" action-id)))
        #"ExtensionStatus"
        (let [status (int->exten-status (event :status))
              agnt (digits (event :exten))]
          (locking lock
            (update-agnt-state agnt assoc :exten-status status)
            (push-event agnt "extensionStatus" status)))
        #"QueueMemberStatus"
        (locking lock
          (let [q (event :queue), agnt (digits (event :location)), st (event->member-status event)]
            (when (update-one-agent-amiq-status agnt q st)
              (push-event agnt "queueMemberStatus" {q st}))))
        #"QueueMember(Add|Remov)ed" :>>
        #(let [q (event :queue), agnt (digits (event :location))
               st (if (= (% 1) "Add") (event->member-status event) "loggedoff")]
           (update-agnt-state agnt assoc-in [:amiq-status q] st)
           (push-event agnt "queueMemberStatus" {q st}))
        #"QueueMemberPaused"
        (let [q (event :queue), agnt (digits (event :location))
              paused? (event :paused)]
          (schedule #(let [st (if paused? "paused" ((agnt-qs-status agnt [q]) q))]
                       (when (update-one-agent-amiq-status agnt q st)
                         (push-event agnt "queueMemberStatus" {q st})))))
        nil))))

(defn- actionid [] (<< "pbxis-~(.substring (ticket) 0 8)"))

(defn originate-call
  "Issues a request to originate a call to the supplied phone number and
patch it through to the supplied agent's extension.

Returns the ID (string) of the request. The function returns immediately
and doesn't wait for the call to be established. In the case of failure,
an event will be received by the client referring to this ID.

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

     :channel-prefix -- string to prepend to agent's extension
       in order to form the agent \"location\" as known to
       Asterisk. This is typically \"SCCP/\", \"SIT/\" or similar.

     :originate-context -- dialplan context used for call origination.
       Typically the default context is called \"default\".

     :originate-timeout-seconds -- time to wait for the called party to
       answer the phone.

     :poll-timeout-seconds -- timeout value for the events-for function.

     :unsub-delay-seconds -- how long to keep accumulating events into
       the event channel while no consumers are attached.

     :agent-gc-delay-minutes -- grace period within which to keep
       tracking the state of an agent after he was automatically
       unsubscribed due to client inactivity (events-for not getting
       called within half of poll-timeout).  Some state cannot be
       regenerated later, such as the phone number of the party called
       by originate-call."
  [host username password cfg]
  (locking ami-connection
    (swap! config merge cfg)
    (let [c (reset! ami-connection
                    (-> (ManagerConnectionFactory. host username password)
                        .createManagerConnection))]
      (doto c (.addEventListener ami-listener) .login))))

(defn ami-disconnect
"Disconnects from the AMI and releases all resources."
  []
  (locking ami-connection
    (when-let [c @ami-connection] (reset! ami-connection nil) (.logoff c))
    (doseq [a [amiq-cnt-agnts agnt-state ticket-agnt agnt-unsubscriber agnt-gc memory]]
      (reset! a {}))))
