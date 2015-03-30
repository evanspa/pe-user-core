(ns user
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer (pprint)]
            [clojure.repl :refer :all]
            [clj-time.core :as t]
            [clojure.test :as test]
            [datomic.api :as d]
            [clojure.stacktrace :refer (e)]
            [datomic.api :refer [q db] :as d]
            [pe-user-core.core :as core]
            [pe-datomic-utils.core :as ducore]
            [cemerick.friend.credentials :refer [hash-bcrypt bcrypt-verify]]
            [pe-user-core.test-utils :refer [user-schema-files
                                             db-uri
                                             user-partition]]
            [clojure.tools.namespace.repl :refer (refresh refresh-all)]))

(def conn (atom nil))

(defn refresh-db
  []
  (reset! conn (ducore/refresh-db db-uri user-schema-files))
  (ducore/transact-partition @conn user-partition))

(defn save-new-authtoken
  [conn u-entid expiration-date]
  (let [[plaintext-token txnmap] (core/create-and-save-auth-token-txnmap user-partition
                                                                         u-entid
                                                                         expiration-date)
        tx @(d/transact conn [txnmap])]
    [plaintext-token (d/resolve-tempid (d/db conn) (:tempids tx) (:db/id txnmap))]))

(defn save-new-user
  [conn user]
  (ducore/save-new-entity conn (core/save-new-user-txnmap user-partition user)))
