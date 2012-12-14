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
                     :originate-timeout-seconds 45
                     :unsub-delay-seconds 15
                     :agent-gc-delay-minutes 60})

(defn- unsub-delay [] [(@config :unsub-delay-seconds) TimeUnit/SECONDS])
(defn- agnt-gc-delay [] [(@config :agent-gc-delay-minutes) TimeUnit/MINUTES])
(defn- originate-timeout [] (@config :originate-timeout-seconds))
(def DUE-EVENT-WAIT-SECONDS 5)

(defonce ^:private lock (Object.))

(defonce ^:private amiq-cnt (ref {}))

(defonce ^:private event-hub-receiver (m/channel))

(defonce ^:private event-hub-emitter
  (let [ch (m/channel* :grounded? true :permanent? true :description "Event hub")]
    (m/siphon (m/filter*
               (fn [e] (if (not= (:type e) "queueCount")
                         true
                         (dosync
                          (let [q (e :queue), cnt (@amiq-cnt q), newcnt (e :count)]
                            (when (not= (or cnt 0) newcnt)
                              (if (= newcnt 0)
                                (alter amiq-cnt dissoc q)
                                (alter amiq-cnt assoc q newcnt))
                              true)))))
               event-hub-receiver)
              ch)
    ch))

(defonce ^:private ami-connection (atom nil))

(defonce ^:private agnt-state (atom {}))

(defonce ^:private agnt-unsubscriber (atom {}))

(defonce ^:private agnt-gc (atom {}))

(defonce ^:private memory (atom {}))

(defn- >?> [& fs] (reduce #(when %1 (%1 %2)) fs))

(defn- call [f] (f))

(defn close-permanent [ch]
  (when-let [n (and ch (chan/receiver-node ch))]
    (node/set-state! n ^NodeState (node/state n) :permanent? false)
    (m/close ch)))

(defn swap-update! [atom path new-val]
  (let [swapped-in (swap! atom update-in path #(if-not (= % new-val) new-val %))]
    (not (identical? new-val (apply >?> swapped-in path)))))

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

(defn- schedule [task delay unit]
  (m/run-pipeline nil
                  (m/wait-stage (->> delay (.toMillis unit)))
                  (fn [_] (task))))

(defn- cancel [async-result] (when async-result (m/enqueue async-result :cancel)))

(defn- remember [k v timeout]
  (swap! memory assoc k v)
  (schedule #(swap! memory dissoc k) timeout TimeUnit/SECONDS))

(defn- recall [k] (when-let [v (@memory k)] (swap! memory dissoc k) v))

(defn- agnt->location [agnt] (when agnt (str (@config :location-prefix) agnt)))

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

(defn- set-schedule [task-atom agnt delay task]
  (let [newsched (when delay
                   (apply schedule #(locking lock (swap! task-atom dissoc agnt) (task)) delay))]
    (swap! task-atom update-in [agnt] #(do (cancel %) newsched))))

(declare ^:private config-agnt)

(defn- reschedule-agnt-gc [agnt]
  (set-schedule agnt-gc agnt (agnt-gc-delay) #(do (logdebug "GC" agnt) (config-agnt agnt []))))

(defn- set-agnt-unsubscriber-schedule [agnt reschedule?]
  (set-schedule agnt-unsubscriber agnt (when reschedule? (unsub-delay))
                #(locking lock (-?> (@agnt-state agnt) :unsub-fn call))))

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

(defn- full-agent-amiq-status []
  (loginfo "Fetching full Asterisk queue status")
  (locking lock
    (let [now-agnt-state @agnt-state
          amiq-status (mapcat #(amiq-status % nil) (keys @amiq-cnt))
          all-agnts-qstatus (reduce (fn [m [q-st & members]]
                                      (reduce #(assoc-in %1 [(digits (:location %2)) (:queue q-st)]
                                                         (event->member-status %2))
                                              m members))
                                    {}
                                    amiq-status)
          known-agnts (keys @agnt-state)]
      {:amiq-cnt (into {} (for [[q-st] amiq-status] [(:queue q-st) (:calls q-st)]))
       :agnt-amiqstatus
       (into (dissoc all-agnts-qstatus known-agnts)
             (reduce (fn [agnt-qstatus agnt]
                       (assoc agnt-qstatus agnt
                              #(merge (into {} (for [q (keys %)] [q "loggedoff"]))
                                      (all-agnts-qstatus agnt))))
                     {}
                     known-agnts))})))

(defn- publish [event] (m/enqueue event-hub event))

(defn- make-event [target-type target event-type & {:as data}]
  (merge {:type event-type, target-type target} data))

(defn agnt-event [agnt type & data] (apply make-event :agent agnt type data))

(defn member-event [agnt amiq status]
  (make-event :agent agnt "queueMemberStatus" :queue amiq :status status))

(defn- call-event [agnt id phone-num]
  (agnt-event (digits agnt) "phoneNumber" :call-id id :number phone-num))

(defn- qcount-event [ami-event]
  (make-event :queue (ami-event :queue) "queueCount" :count (ami-event :count)))

(defn- close-sinkch [sinkch agnt] (doto sinkch
                                    (m/enqueue (make-event :agent agnt "closed"))
                                    (m/close)))

(defn- pbxis-event-handler [agnt]
  (fn [e]
    (locking lock
      (condp = (e :type)
        "phoneNumber"
        (let [{:keys [number unique-id]} e]
          (if number
            (do (update-agnt-state agnt assoc-in [:calls unique-id] number) e)
            (do (update-agnt-state agnt dissoc-in [:calls unique-id])
                (assoc e :number (-?> (@agnt-state agnt) :calls first val)))))
        "callsInProgress"
        (do (update-agnt-state agnt assoc-in [:calls] (e :data))
            nil)
        "extensionStatus" (when (swap-update! [agnt :exten-status] (String. (e :status)))
                            e)
        "queueMemberStatus"
        (when (swap-update! agnt-state [agnt :amiq-status (e :queue)] (String. (e :status)))
          e)
        "agentComplete" e
        "originateFailed" e
        "queueCount" e
        #_else nil))))

(defn config-agnt
  "Sets up an event channel for the supplied agent. If a channel for
   the agent already exists, closes it and replaces with a new one,
   but keeps all other tracked state. If no sink is attached within
   unsub-delay-seconds, the event channel is closed, but other state
   for the agent will be tracked for another agent-gc-delay-minutes.

   agnt: agent's phone extension number.
   qs: a collection of agent queue names. If empty or nil, the agent is
       unsubscribed.

   Returns a lamina result channel that will be realized when the
   event channel is closed, either by timeout or by another
   call to this function involving the same agent.

  In case the agent was unsubscribed, returns nil."
  [agnt qs]
  (loginfo "config-agnt" agnt qs)
  (locking lock
    (let [now-state (@agnt-state agnt)]
      (when now-state
        (logdebug "Cancel any existing subscription for" agnt)
        (-?> (now-state :sinkch) (close-sinkch agnt))
        (-?> (now-state :unsub-fn) call))
      (if (seq qs)
        (let [eventch (m/permanent-channel)
              q-set (set qs)
              unsub-result (m/result-channel)
              unsub-fn (fn [] (do (m/enqueue unsub-result ::closed)
                                  (update-agnt-state agnt update-in [:eventch]
                                                     #(if (identical? eventch %)
                                                        (do (close-permanent eventch)
                                                            (logdebug "Unsubscribe" agnt)
                                                            nil)
                                                        %))))]
          (if now-state
            (update-agnt-state agnt assoc :eventch eventch, :unsub-fn unsub-fn)
            (swap! agnt-state assoc agnt
                   {:eventch eventch, :unsub-fn unsub-fn
                    :exten-status (exten-status agnt) :calls (calls-in-progress agnt)}))
          (when (not= q-set (-?> now-state :amiq-status keys set))
            (update-agnt-state agnt assoc :amiq-status (agnt-qs-status agnt qs)))
          (let [st (@agnt-state agnt)]
            (set-agnt-unsubscriber-schedule agnt true)
            (reschedule-agnt-gc agnt)
            (let [enq #(m/enqueue eventch (apply make-event :agent agnt %&))]
              (enq "extensionStatus" :status (st :exten-status))
              (enq "phoneNumber" :number (-?> st :calls first val)))
            (doseq [[amiq status] (st :amiq-status)]
              (m/enqueue eventch (member-event agnt amiq status)))
            (doseq [[amiq cnt] (into {} (concat (for [q qs] [q 0]) (select-keys @amiq-cnt qs)))]
              (m/enqueue eventch (make-event :queue amiq "queueCount" :count cnt))))
          (m/siphon
           event-hub
           (m/siphon->>
            (m/filter* #(or (= agnt (% :agent)) (q-set (% :queue))))
            (m/map* (pbxis-event-handler agnt))
            (m/filter* (comp not nil?))
            eventch))
          unsub-result)
        (do (close-permanent (>?> @agnt-state agnt :eventch))
            (swap! agnt-state dissoc agnt) nil)))))

(defn attach-sink
  "Attaches the supplied lamina channel to the agent's event channel, if present.
   Closes any existing sink channel. When the sink channel is
   closed (including an error state), the unsubscribe countdown
   starts. If a new sink is registered within unsub-delay-seconds, it
   will resume reception with the event following the last one
   enqueued into the closed sink."
  [sinkch agnt]
  (locking lock
    (when-let [eventch (and sinkch (>?> @agnt-state agnt :eventch))]
      (set-schedule agnt-gc agnt nil nil)
      (update-agnt-state agnt update-in [:sinkch]
                         #(do (when % (close-sinkch % agnt)) sinkch))
      (logdebug "Attach sink" agnt)
      (m/on-closed sinkch
                   #(locking lock
                      (logdebug "Closed sink" agnt)
                      (when [(identical? sinkch (>?> @agnt-state agnt :sinkch))]
                        (update-agnt-state agnt dissoc :sinkch)
                        (reschedule-agnt-gc agnt)
                        (set-agnt-unsubscriber-schedule agnt true))))
      (m/siphon eventch sinkch)
      (set-agnt-unsubscriber-schedule agnt false)
      sinkch)))

(def ^:private ignored-events
  #{"VarSet" "NewState" "NewChannel" "NewExten" "NewCallerId" "NewAccountCode" "ChannelUpdate"
    "RtcpSent" "RtcpReceived" "PeerStatus"})

(def ^:private activating-eventfilter (atom nil))

(defn- handle-ami-event [event]
  (let [t (:event-type event), unique-id (:uniqueId event)]
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
      (logdebug 'ami-event "AMI event\n" t (dissoc event :event-type :privilege)))
    (condp re-matches t
      #"Connect"
      (ex/task (let [{:keys [agnt-amiqstatus amiq-cnt]} (full-agent-amiq-status)
                     calls-in-progress (calls-in-progress)]
                 (doseq [[agnt amiq-status] agnt-amiqstatus, [amiq status] amiq-status]
                   (publish (member-event agnt amiq status)))
                 (doseq [[amiq cnt] amiq-cnt] (publish (qcount-event {:queue amiq :count cnt})))
                 (doseq [[agnt calls] calls-in-progress]
                   (publish (agnt-event agnt "callsInProgress" calls)))))
      #"Join|Leave"
      (publish (qcount-event event))
      #"Dial"
      (when (= (event :subEvent) "Begin")
        (publish (call-event (event :channel) (event :srcUniqueId) (-> event :dialString digits)))
        (publish (call-event (event :destination) (event :destUniqueId)
                             (or (recall (event :srcUniqueId)) (event :callerIdNum)))))
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
      (publish (member-event (digits (event :location)) (event :queue)
                             (event->member-status event)))
      #"QueueMember(Add|Remov)ed" :>>
      #(publish (member-event (digits (event :location)) (event :queue)
                              (if (= (% 1) "Add") (event->member-status event) "loggedoff")))
      #"QueueMemberPaused"
      (let [agnt (digits (event :location)), q (event :queue)
            pub #(publish (member-event agnt q %))]
        (if (event :paused)
          (pub "paused")
          (ex/task (pub ((agnt-qs-status agnt [q]) q)))))
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
       answer the phone.

     :unsub-delay-seconds -- how long to keep accumulating events into
       the event channel while no consumers are attached.

     :agent-gc-delay-minutes -- grace period within which to keep
       tracking the state of an agent after he was automatically
       unsubscribed due to client inactivity (long-poll not getting
       called within unsub-delay-seconds).  Some state cannot be
       regenerated later, such as the phone number of the party called
       by originate-call."
  [host username password cfg]
  (locking ami-connection
    (reset! config (spy "PBXIS config" (merge default-config cfg)))
    (let [c (reset! ami-connection
                    (-> (ManagerConnectionFactory. host username password)
                        .createManagerConnection))]
      (doto c (.addEventListener ami-listener) .login))))

(defn ami-disconnect
  "Disconnects from the AMI and releases all resources."
  []
  (locking ami-connection
    (when-let [c @ami-connection] (reset! ami-connection nil) (.logoff c))
    (doseq [a [amiq-cnt-agnts agnt-state agnt-unsubscriber agnt-gc memory]]
      (reset! a {}))))
