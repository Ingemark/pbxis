(ns com.ingemark.logging
  (require (clojure [stacktrace :as trc] [pprint :as pp] [string :as s]))
  (import (org.slf4j Logger LoggerFactory)))

(defn pprint-str [& args]
  (s/join " "
          (for [a args]
            (cond
             (nil? a) "#nil"
             (or (coll? a) (-> a type .isArray)) (pp/write (if (map? a) a (seq a)) :stream nil)
             (instance? Throwable a)
             (str "\n" (with-out-str (try (trc/print-cause-trace a)
                                          (catch Throwable _
                                            (println (type a) (.getMessage a)
                                                     "[cannot show stacktrace]")))))
             :else a))))

(defn- body-for-log [logger-sym level args]
  `((. ~logger-sym ~(symbol level) (pprint-str ~@args))))

(defn- body-for-spy [logger-sym level args]
  `((let [evald-args# [~@args]]
      (. ~logger-sym ~(symbol level) (apply pprint-str evald-args#))
      (peek evald-args#))
    ~(last args)))

(defn- log [body-fn level & args]
  (let [firstarg (first args)
        explicit-name? (and (seq? firstarg) (= 'quote (first firstarg)))
        logger-name (if explicit-name?
                      `(name ~firstarg)
                      (-> *ns* ns-name name))
        args (if explicit-name? (next args) args)
        logger-sym (gensym "logger")
        is-level-enabled (symbol (str "is" (s/capitalize level) "Enabled"))]
    `(let [~logger-sym (LoggerFactory/getLogger ~logger-name)]
       (if (. ~logger-sym ~is-level-enabled) ~@(body-fn logger-sym level args)))))


(def #^{:private true} add-to-doc
     "\nIf the first argument is a quoted symbol, it will be used for the logger name.
Otherwise the logger name is the name of the current namespace.")

(defn- doc-spy [level]
  (str "Logs, at " level " level, the message formed by concatenating the string
representations of supplied arguments, returning the value of the last
argument. If " level " level is not enabled, evaluates only the last argument." add-to-doc))

(defmacro spy [& args]
  (apply log body-for-spy "debug" args))

(defmacro spy* [& args]
  (apply log body-for-spy "info" args))


(defn- doc-log [level]
  (str "Logs, at " level " level, the message formed by concatenating the
string representations of supplied arguments. Does not evaluate the
arguments unless the " level " level is enabled." add-to-doc))

(defmacro logdebug [& args]
  (apply log body-for-log "debug" args))

(defmacro loginfo [& args]
  (apply log body-for-log "info" args))

(defmacro logwarn [& args]
  (apply log body-for-log "warn" args))

(defmacro logerror [& args]
  (apply log body-for-log "error" args))


(defmacro add-doc {:private true} [name docstring]
  `(alter-meta! (var ~name)  assoc :doc ~docstring))

(add-doc logdebug (doc-log "DEBUG"))
(add-doc loginfo (doc-log "INFO"))
(add-doc logwarn (doc-log "WARN"))
(add-doc logerror (doc-log "ERROR"))
(add-doc spy (doc-spy "DEBUG"))
(add-doc spy* (doc-spy "INFO"))
