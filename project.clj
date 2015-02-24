(defproject com.ingemark/pbxis "1.0.0"
  :description "Asterisk Call Center "
  :url "https://github.com/Inge-mark/pbxis"
  :license {:name "Eclipse Public License" :url "http://www.eclipse.org/legal/epl-v10.html"}
  :lein-release {:deploy-via :lein-deploy-clojars}

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.incubator "0.1.2"]
                 [lamina "0.5.0-beta15"]
                 [org.slf4j/slf4j-api "1.7.2"]
                 [org.slf4j/log4j-over-slf4j "1.7.2"]
                 [ch.qos.logback/logback-classic "1.0.9"]
                 [org.asteriskjava/asterisk-java "1.0.0.M3"]]
  :exclusions [log4j/log4j]
  :aliases {"release" ["xdo" "git-check-clean"
                       ["thrush" "version-update" ":release," "edit-version"]
                       ["deploy" "clojars"] ["commit" "New release"] "tag"
                       ["thrush" "version-update" ":new-snapshot," "edit-version"]
                       ["commit" "New snapshot"] "push"]}
  :plugins [[lein-nix "0.1.9"]]
  :jvm-opts ["-Dlogback.configurationFile=logback.xml"]
  :repl-options {:init-ns com.ingemark.pbxis})
