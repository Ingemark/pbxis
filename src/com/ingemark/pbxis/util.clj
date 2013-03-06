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

(ns com.ingemark.pbxis.util
  (require (lamina [core :as m] [api :as ma] [executor :as ex])
           [lamina.core.channel :as chan]
           [com.ingemark.logging :refer :all]
           [clojure.string :as s]
           [clojure.core.strint :refer (<<)])
  (import java.util.concurrent.TimeUnit (clojure.lang Reflector RT)))

(defonce memory (atom {}))

(defn >?> [& fs] (reduce #(when %1 (%1 %2)) fs))

(defn enq [ch event] (when event (m/enqueue ch event)))

(defn to-millis [^Long t ^TimeUnit unit] (.toMillis unit t))

(defn leech [src dest] (ma/connect src dest false true))

(defn pipeline-stage [src]
  (let [ch* (chan/mimic src)]
    (ma/bridge-join src ch* "pipeline-stage" (m/pipeline {:unwrap? true} #(m/enqueue ch* %)))
    ch*))

(defn pipelined [f] (fn [& args] (pipeline-stage (apply f args))))

(defn invoke [^String m o & args]
  (Reflector/invokeInstanceMethod o m (into-array Object args)))

(defn camelize [s] (s/replace (name s) #"-(\w)" (comp s/upper-case second)))

(defn upcamelize [s] (s/replace (name s) #"(?:-|^)(\w)" (comp s/upper-case second)))

(defn event-bean [event]
  (into (sorted-map)
        (-> (bean event)
            (dissoc :dateReceived :application :server :priority :appData :func :class
                    :source :timestamp :line :file :sequenceNumber :internalActionId)
            (assoc :event-type
              (let [c (-> event .getClass .getSimpleName)]
                (.substring c 0 (- (.length c) (.length "Event"))))))))

(defn action [type params]
  (logdebug 'ami-event (<< "(action \"~{type}\" ~{params})"))
  (let [a (Reflector/invokeConstructor
           (RT/classForName (<< "org.asteriskjava.manager.action.~{type}Action"))
           (object-array 0))]
    (doseq [[k v] params] (invoke (<< "set~(upcamelize k)") a v))
    a))

(defn schedule [task delay unit]
  (m/run-pipeline nil (m/wait-stage (to-millis delay unit)) (fn [_] (task))))

(defn cancel-schedule [async-result] (when async-result (m/enqueue async-result :cancel)))

(defn remember [k v timeout]
  (logdebug (<< "(remember ~{k} ~{v})"))
  (swap! memory assoc k v)
  (schedule #(swap! memory dissoc k) timeout TimeUnit/SECONDS)
  nil)

(defn recall [k]
  (spy (<< "(recall ~{k}) ->") (when-let [v (@memory k)] (swap! memory dissoc k) v)))

(defn set-schedule [task-atom agnt delay task]
  (let [newsched (when delay
                   (apply schedule #(swap! task-atom dissoc agnt) (task) delay))]
    (swap! task-atom update-in [agnt] #(do (cancel-schedule %) newsched))))

(defn extend-with-identity [m] #(str (m % %)))

(def int->exten-status
  (extend-with-identity
    {0 "not_inuse" 1 "inuse" 2 "busy" 4 "unavailable" 8 "ringing" 9 "ringinuse" 16 "onhold"}))

(def int->channel-status
  (extend-with-identity
    {2 "OffHook" 3 "Dialing" 4 "Ring" 5 "Ringing" 6 "Up" 7 "Busy"}))

(defn event->member-status [event]
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

(defn digits [s] (re-find #"\d+" (or s "")))

(defn make-event [target-type target event-type & {:as data}]
  (merge {:type event-type, target-type target} data))

(defn agnt-event [agnt type & data] (apply make-event :agent agnt type data))

(defn member-event [agnt q status]
  (when status (make-event :agent agnt "queueMemberStatus" :queue q :status status)))

(defn name-event [agnt name]
  (when name (make-event :agent (digits agnt) "agentName" :name name)))

(defn call-event [agnt unique-id phone-num & [name]]
  (agnt-event (digits agnt) "phoneNumber" :unique-id unique-id :number phone-num :name name))

(defn qcount-event [q cnt] (make-event :queue q "queueCount" :count cnt))

(defn actionid [] (<< "pbxis-~(.substring (-> (java.util.UUID/randomUUID) .toString) 0 8)"))
