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
           [com.ingemark.logging :refer :all]
           (clojure.core [incubator :refer (-?> -?>> dissoc-in)] [strint :refer (<<)]))
  (import (org.asteriskjava.manager ManagerConnectionFactory ManagerEventListener)
          org.asteriskjava.manager.event.ManagerEvent
          (java.util.concurrent LinkedBlockingQueue TimeUnit)
          (clojure.lang Reflector RT)))

(defonce config (atom {:channel-prefix "SCCP/"
                       :originate-context "default"
                       :originate-timeout-seconds 45
                       :poll-timeout-seconds 5
                       :agent-gc-delay-minutes 1}))

(defn- poll-timeout [] (@config :poll-timeout-seconds))
(defn- unsub-delay [] [(inc (quot (poll-timeout) 2)) TimeUnit/SECONDS])
(defn- agnt-gc-delay [] [(@config :agent-gc-delay-minutes) TimeUnit/MINUTES])
(defn- originate-timeout [] (@config :originate-timeout-seconds))
(def EVENT-BURST-MILLIS 100)
(def DUE-EVENT-WAIT-SECONDS 5)

(defn- empty-q [] (LinkedBlockingQueue.))

(defonce ^:private lock (Object.))

(defonce scheduler (atom nil))

(defonce ami-connection (atom nil))

(defonce ^:private amiq-cnt-agnts (atom {}))

(defonce ^:private agnt-state (atom {}))

(defonce ^:private rndkey-agnt (atom {}))

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
  (.schedule @scheduler task (or delay 0) (or unit TimeUnit/SECONDS)))

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
                     (swap! rndkey-agnt dissoc (now-state :rndkey))
                     (update-agnt-state agnt dissoc :rndkey :eventq)))))

(defn- rndkey [] (-> (java.util.UUID/randomUUID) .toString))

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

(defn- replace-rndkey [agnt old new]
  (swap! rndkey-agnt #(-> % (dissoc old) (assoc new agnt)))
  (update-agnt-state agnt assoc :rndkey new))

(defn- enq-event [agnt k & vs]
  (when-let [q (-?> (@agnt-state (digits agnt)) :eventq)]
    (.add q (spy "enq-event" agnt (vec (cons k vs))))))

(defn config-agnt [agnt qs]
  (logdebug "config-agnt" agnt qs)
  (locking lock
    (swap! amiq-cnt-agnts update-amiq-cnt-agnts agnt qs)
    (let [now-state (@agnt-state agnt)]
      (if (seq qs)
        (let [rndkey (rndkey), eventq (empty-q)]
          (set-agnt-unsubscriber-schedule agnt true)
          (reschedule-agnt-gc agnt)
          (if now-state
            (do (replace-rndkey agnt (:rndkey now-state) rndkey)
                (update-agnt-state agnt assoc :eventq eventq)
                (-?> now-state :eventq (.add ["requestInvalidated"])))
            (do (swap! rndkey-agnt assoc rndkey agnt)
                (swap! agnt-state assoc agnt
                       {:rndkey rndkey :eventq eventq :exten-status (exten-status agnt)
                        :calls (calls-in-progress agnt)})))
          (when (not= (set qs) (-?> now-state :amiq-status keys set))
            (update-agnt-state agnt assoc :amiq-status (agnt-qs-status agnt qs)))
          (let [st (@agnt-state agnt)]
            (enq-event agnt "extensionStatus" (st :exten-status))
            (enq-event agnt "queueMemberStatus" (st :amiq-status))
            (enq-event agnt "phoneNum" (-?> st :calls first val)))
          (enq-event agnt "queueCount"
                     (into {} (for [[amiq {:keys [cnt]}] (select-keys @amiq-cnt-agnts qs)] [amiq cnt])))
          rndkey)
        (do
          (swap! rndkey-agnt dissoc (:rndkey now-state))
          (swap! agnt-state dissoc agnt)
          (<< "Agent ~{agnt} unsubscribed"))))))

(defn events-for [agnt-key]
  (when-let [[agnt q newkey] (locking lock
                               (when-let [agnt (@rndkey-agnt agnt-key)]
                                 (when-let [q (>?> @agnt-state agnt :eventq)]
                                   (reschedule-agnt-gc agnt)
                                   (let [rndkey (rndkey)]
                                     (replace-rndkey agnt agnt-key rndkey)
                                     (set-agnt-unsubscriber-schedule agnt false)
                                     [agnt q rndkey]))))]
    (try
      (let [drain-events #(.drainTo q %)
            evs (doto (java.util.ArrayList.) drain-events)]
        (when-not (seq evs)
          (when-let [head (.poll q (poll-timeout) TimeUnit/SECONDS)]
            (Thread/sleep EVENT-BURST-MILLIS)
            (doto evs (.add head) drain-events)))
        (spy "Events for" agnt {:key newkey :events evs}))
      (finally (when (= newkey (>?> @agnt-state agnt :rndkey))
                 (set-agnt-unsubscriber-schedule agnt true))))))

(defn- broadcast-qcount [ami-event]
  (locking lock
    (let [amiq (ami-event :queue), cnt (ami-event :count)]
      (when-let [cnt-agnts (@amiq-cnt-agnts amiq)]
        (swap! amiq-cnt-agnts assoc-in [amiq :cnt] cnt)
        (doseq [agnt (cnt-agnts :agnts)] (enq-event agnt "queueCount" {amiq cnt}))))))

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
    (enq-event agnt "phoneNum" (or phone-num (-?> (@agnt-state agnt) :calls first val)))))

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
                     (doseq [[agnt upd] agnt-amiqstatus] (enq-event agnt "queueMemberStatus" upd))
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
        (enq-event (event :member) "agentComplete"
                   unique-id (event :talkTime) (event :holdTime)
                   (-?> event :variables (.get "FILEPATH")))
        #"OriginateResponse"
        (let [action-id (event :actionId)]
          (if (= (event :response) "Success")
            (remember unique-id (recall action-id) DUE-EVENT-WAIT-SECONDS)
            (enq-event (event :exten) "placeCallFailed" action-id)))
        #"ExtensionStatus"
        (let [status (int->exten-status (event :status))
              agnt (digits (event :exten))]
          (locking lock
            (update-agnt-state agnt assoc :exten-status status)
            (enq-event agnt "extensionStatus" status)))
        #"QueueMemberStatus"
        (locking lock
          (let [q (event :queue), agnt (digits (event :location)), st (event->member-status event)]
            (when (update-one-agent-amiq-status agnt q st)
              (enq-event agnt "queueMemberStatus" {q st}))))
        #"QueueMember(Add|Remov)ed" :>>
        #(let [q (event :queue), agnt (digits (event :location))
               st (if (= (% 1) "Add") (event->member-status event) "loggedoff")]
           (update-agnt-state agnt assoc-in [:amiq-status q] st)
           (enq-event agnt "queueMemberStatus" {q st}))
        #"QueueMemberPaused"
        (let [q (event :queue), agnt (digits (event :location))
              paused? (event :paused)]
          (schedule #(let [st (if paused? "paused" ((agnt-qs-status agnt [q]) q))]
                       (when (update-one-agent-amiq-status agnt q st)
                         (enq-event agnt "queueMemberStatus" {q st})))))
        nil))))

(defn- actionid [] (<< "pbxis-~(.substring (rndkey) 0 8)"))

(defn originate-call [agnt phone]
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
      "Placing call to ~{phone}")))

(defn queue-action [type agnt params]
  (send-action (action (<< "Queue~(upcase-first type)")
                       (assoc params :interface (agnt->location agnt)))))

(def ami-listener
  (reify ManagerEventListener
    (onManagerEvent [_ event] (handle-ami-event (event-bean event)))))

(defn ami-connect [host username password cfg]
  (locking ami-connection
    (let [c (reset! ami-connection
                    (-> (ManagerConnectionFactory. host username password)
                        .createManagerConnection))]
      (doto c (.addEventListener ami-listener) .login))
    (swap! config merge cfg)
    (reset! scheduler (java.util.concurrent.Executors/newSingleThreadScheduledExecutor))))

(defn ami-disconnect []
  (locking ami-connection
    (when-let [c @ami-connection] (reset! ami-connection nil) (.logoff c))
    (reset! scheduler nil)
    (doseq [a [amiq-cnt-agnts agnt-state rndkey-agnt agnt-unsubscriber agnt-gc memory]]
      (reset! a {}))))
