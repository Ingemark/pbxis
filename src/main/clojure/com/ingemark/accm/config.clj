(ns com.ingemark.accm.config
  (:use (com.ingemark.clojure (defrecord :only (the)))
        com.ingemark.accm.types
        (clojure.java (io :as io :only ())))
  (:import com.ingemark.accm.types.User
           (java.io FileInputStream PushbackReader)))

(def _settings (atom nil))

(defn initialize []
  (let [users (with-open [uis (FileInputStream. (or (System/getProperty "users.file")
                                                    "../properties/users.clj"))]
                (read (PushbackReader. (io/reader uis))))
        properties (with-open [pis (FileInputStream. (or (System/getProperty "properties.file")
                                                         "../properties/properties.clj"))]
                     (read (PushbackReader. (io/reader pis))))]
    (reset! _settings (assoc properties :users (map #(the User %) users)))))

(defn settings
  ([] @_settings)
  ([k] (get @_settings k)))