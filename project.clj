(defproject pbxis "0.1.0-SNAPSHOT"
  :description "Asterisk Call Center integration module"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/core.incubator "0.1.2"]
                 [slingshot/slingshot "0.10.3"]
                 [org.clojure/data.json "0.1.3"]
                 [com.ingemark.clojure/im-config-utils "3.2"]
                 [com.ingemark.clojure/im-logger-utils "5.3"]
                 [ch.qos.logback/logback-classic "1.0.3"]
                 [net.cgrand/moustache "1.1.0"]
                 [ring/ring-jetty-adapter "1.1.0"]
                 [ring-json-params/ring-json-params "0.1.3"]
                 [org.asteriskjava/asterisk-java "1.0.0.M3"
                  :exclusions [[org.slf4j/slf4j-api]
                               [log4j/log4j]]]
                 [org.slf4j/log4j-over-slf4j "1.6.4"]]
  :plugins [[lein-swank "1.4.4"]]
  :repositories [["ingemark-repository" "http://dev.inge-mark.hr/downloads/m2"]
                 ["clojure" "http://build.clojure.org/releases"]
                 ["clojars" "http://clojars.org/repo"]])
