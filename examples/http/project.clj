(defproject com.ingemark/pbxis "0.1.0-SNAPSHOT"
  :description "Call Center Demo over HTTP with long polling"
  :url "http://www.inge-mark.hr"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/core.incubator "0.1.2"]
                 [org.clojure/data.json "0.1.3"]
                 [net.cgrand/moustache "1.1.0"]
                 [ring/ring-jetty-adapter "1.1.0" :exclusions [javax.servlet/servlet-api]]
                 [ring-json-params/ring-json-params "0.1.3"]
                 [org.slf4j/slf4j-api "1.6.1"]
                 [ch.qos.logback/logback-classic "1.0.3"]
                 [com.ingemark/pbxis "0.1.0-SNAPSHOT"]]
  :plugins [[lein-swank "1.4.4"]])
