(defproject com.ingemark/pbxis "2.0.9-SNAPSHOT"
  :description "Asterisk Call Center Adapter"
  :url "https://github.com/Inge-mark/pbxis"
  :license {:name "Apache License, Version 2.0"}
  :lein-release {:deploy-via :lein-deploy-clojars}

  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.incubator "0.1.2"]
                 [potemkin "0.4.5"] ; overriding potemkin version referenced by
                                    ; Lamina because it doesn't seem to work
                                    ; with Clojure 1.9.0
                 [lamina "0.5.6"]
                 [org.slf4j/slf4j-api "1.7.25"]
                 [org.slf4j/log4j-over-slf4j "1.7.25"]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 [org.asteriskjava/asterisk-java "2.0.3"]]
  :profiles {:dev {:dependencies [[org.clojure/test.check "0.10.0-alpha3"]
                                  [org.clojure/spec.alpha "0.2.176"]
                                  [org.clojure/core.specs.alpha "0.2.44"]
                                  [orchestra "2018.12.06-2"]]}}
  :exclusions [log4j/log4j]
  :aliases {"release" ["xdo" "git-check-clean"
                       ["thrush" "version-update" ":release," "edit-version"]
                       ;["deploy" "clojars"] 
                       ["commit" "New release"] "tag"
                       ["thrush" "version-update" ":new-snapshot," "edit-version"]
                       ["commit" "New snapshot"] "push"]}
  :plugins [[im-lein-nix "0.1.13"]]
  :jvm-opts ["-Dlogback.configurationFile=logback.xml"]
  :repl-options {:init-ns com.ingemark.pbxis}
  :repositories [["asterisk-java" "https://raw.githubusercontent.com/asterisk-java/asterisk-java/mvn-repo"]])
