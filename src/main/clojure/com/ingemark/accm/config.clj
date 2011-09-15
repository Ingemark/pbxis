(ns com.ingemark.accm.config
  (:use (com.ingemark.clojure (defrecord :only (the)))
        com.ingemark.accm.types)
  (:import com.ingemark.accm.types.User))

(def users [{:name "Viktor Matić"
             :default-extension "145"
             :default-channel-prefix "SIP/"
             :permissions [:view-all-events]
             :queues ["100"]
             :username "vmatic"
             :password "vmatic"}
            {:name "Josip Gracin"
             :default-extension "139"
             :default-channel-prefix "SIP/"
             :permissions []
             :queues ["100"]
             :username "jgracin"
             :password "jgracin"}])

(def settings
  {:users (map #(the User %) users)
   :ami {:ip-address "192.168.18.30"
         :username   "manager"
         :password   "oracle"}})