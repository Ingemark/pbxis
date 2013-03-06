(defproject com.ingemark/pbxis "0.5.1-alpha1"
  :description "Asterisk Call Center Adapter"
  :url "http://www.inge-mark.hr"
  :license {:name "Eclipse Public License" :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repositories [["releases" {:url "http://dev.inge-mark.hr/downloads/m2"
                              :sign-releases false}]]

  :dependencies [[org.clojure/clojure "1.5.0"]
                 [org.clojure/core.incubator "0.1.2"]
                 [lamina "0.5.0-beta12"]
                 [org.slf4j/slf4j-api "1.7.2"]
                 [org.slf4j/log4j-over-slf4j "1.7.2"]
                 [ch.qos.logback/logback-classic "1.0.9"]
                 [org.asteriskjava/asterisk-java "1.0.0.M3"]]
  :exclusions [log4j/log4j]
  :jvm-opts ["-Dlogback.configurationFile=logback.xml"]
  :repl-options {:init-ns com.ingemark.pbxis})
