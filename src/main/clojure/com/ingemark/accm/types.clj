(ns com.ingemark.accm.types
  (:import (org.asteriskjava.manager ManagerConnectionFactory ManagerConnection ManagerEventListener)
           (java.util.concurrent LinkedBlockingQueue)))

(defrecord ApplicationState [^ManagerConnectionFactory manager-connection-factory
                             ^ManagerConnection manager-connection])

(defrecord User [^String name
                 ^String default-extension
                 ^String default-channel-prefix
                 #_Seq permissions
                 #_Seq<String> queues
                 ^String username
                 ^String password])

(defrecord Listener [^String extension ^LinkedBlockingQueue queue])
