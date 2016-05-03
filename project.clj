(defproject pe-user-core "0.1.41-SNAPSHOT"
  :description "A Clojure library encapsulating an abstraction modeling a user."
  :url "https://github.com/evanspa/pe-user-core"
  :license {:name "MIT"
            :url "http://opensource.org/licenses/MIT"}
  :plugins [[lein-pprint "1.1.2"]
            [codox "0.8.10"]]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/data.codec "0.1.0"]
                 [ch.qos.logback/logback-classic "1.0.13"]
                 [org.slf4j/slf4j-api "1.7.5"]
                 [clj-time "0.8.0"]
                 [org.clojure/java.jdbc "0.5.8"]
                 [com.cemerick/friend "0.2.1"]
                 [clojurewerkz/mailer "1.2.0"]
                 [pe-core-utils "0.0.14"]
                 [pe-jdbc-utils "0.0.21"]]
  :resource-paths ["resources"]
  :codox {:exclude [user]
          :src-dir-uri "https://github.com/evanspa/pe-user-core/blob/0.1.41/"
          :src-linenum-anchor-prefix "L"}
  :profiles {:dev {:source-paths ["dev"]  ;ensures 'user.clj' gets auto-loaded
                   :plugins [[cider/cider-nrepl "0.12.0"]]
                   :dependencies [[org.clojure/tools.namespace "0.2.7"]
                                  [org.clojure/java.classpath "0.2.2"]
                                  [org.clojure/tools.nrepl "0.2.12"]
                                  [org.postgresql/postgresql "9.4.1208.jre7"]
                                  [org.clojure/data.json "0.2.6"]]
                   :resource-paths ["test-resources"]}
             :test {:resource-paths ["test-resources"]}}
  :jvm-opts ["-Xmx1g" "-DUCORE_LOGS_DIR=logs"]
  :repositories [["releases" {:url "https://clojars.org/repo"
                              :creds :gpg}]])
