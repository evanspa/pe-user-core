(ns pe-user-core.test-utils
  (:require [datomic.api :as d]
            [clojure.java.io :refer [resource]]))

(def user-schema-files ["user-schema-updates-0.0.1.dtm"])

(def db-uri "datomic:mem://user")

(def user-partition :user)
