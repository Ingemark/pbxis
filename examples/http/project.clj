(defproject com.ingemark/pbxis-http "0.5.0-SNAPSHOT"
  :description "Asterisk Call Center HTTP Adapter"
  :url "http://www.inge-mark.hr"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.5.0-RC1"]
                 [org.clojure/core.incubator "0.1.2"]
                 [com.ingemark/pbxis "0.5.0-SNAPSHOT"]
                 [net.cgrand/moustache "1.1.0"]
                 [hiccup "1.0.2"]
                 [ring/ring-core "1.1.0" :exclusions [javax.servlet/servlet-api]]
                 [ring-json-params/ring-json-params "0.1.3"]
                 [aleph "0.3.0-beta8"]
                 [org.slf4j/slf4j-api "1.7.2"]
                 [ch.qos.logback/logback-classic "1.0.9"]]
  :main com.ingemark.pbxis-http.main
  :uberjar-name "pbxis-http-standalone.jar"
  :repl-options {:port 4001
                 :init-ns com.ingemark.pbxis-http})
