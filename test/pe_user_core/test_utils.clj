(ns pe-user-core.test-utils)

(def db-name "test_db")

(def subprotocol "postgresql")

(defn db-spec-fn
  ([]
   (db-spec-fn nil))
  ([db-name]
   (let [subname-prefix "//localhost:5432/"]
     {:classname "org.postgresql.Driver"
      :subprotocol subprotocol
      :subname (if db-name
                 (str subname-prefix db-name)
                 subname-prefix)
      :user "postgres"})))

(def db-spec-without-db
  (with-meta
    (db-spec-fn nil)
    {:subprotocol subprotocol}))

(def db-spec
  (with-meta
    (db-spec-fn db-name)
    {:subprotocol subprotocol}))
