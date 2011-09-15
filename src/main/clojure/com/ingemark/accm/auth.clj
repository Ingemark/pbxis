(ns com.ingemark.accm.auth
  (:use (com.ingemark.clojure.ws (utils :only (extract-authorization-params-from-headers)))
        com.ingemark.clojure.logger
        (com.ingemark.accm (config :as config))))

(defn make-unauthorized-response-fn [realm]
  (fn []
    {:status 401
     :headers {"www-authenticate" (format "Basic realm=\"%s\"" realm)
               "pragma" "no-cache"
               "cache-control" "no-cache"}
     :body "Unauthorized"}))

(def ^{:dynamic true} *security-context* {})

(defn- find-user [username]
  (first (filter #(= username (:username %))
                 (config/settings :users))))

(defn basic-auth-wrapper [unauthorized-response-fn validation-fn headers application]
  (fn [request]
    (let [[username password] (extract-authorization-params-from-headers (:headers request))]
      (if-not (validation-fn username password)
        (unauthorized-response-fn)
        (binding [*security-context* (assoc *security-context* :user (find-user username))]
          (application request))))))