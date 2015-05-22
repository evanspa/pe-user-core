(ns pe-user-core.test-utils)

(def db-name "test_db")

(defn db-spec-fn
  ([]
   (db-spec-fn nil))
  ([db-name]
   (let [subname-prefix "//localhost:5432/"]
     {:classname "org.postgresql.Driver"
      :subprotocol "postgresql"
      :subname (if db-name
                 (str subname-prefix db-name)
                 subname-prefix)
      :user (System/getenv "USER")})))

(def db-spec-without-db (db-spec-fn nil))

(def db-spec (db-spec-fn db-name))
