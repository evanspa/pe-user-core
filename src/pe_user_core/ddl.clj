(ns pe-user-core.ddl
  (:require [clojure.java.jdbc :as j]
            [pe-jdbc-utils.core :as jcore]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Table Names
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def tbl-schema-version "schema_version")
(def tbl-user-account "user_account")
(def tbl-auth-token   "authentication_token")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Column Names
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def col-updated-count   "updated_count")
(def col-suspended-at    "suspended_at")
(def col-suspended-count "suspended_count")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constraint Names
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def constr-user-account-uniq-email    "user_account_email_key")
(def constr-user-account-uniq-username "user_account_username_key")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DDL vars
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def schema-version-ddl
  (format "CREATE TABLE IF NOT EXISTS %s (schema_version integer PRIMARY KEY)"
          tbl-schema-version))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Version 0 DDL vars
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def v0-create-user-account-ddl
  (str (format "CREATE TABLE IF NOT EXISTS %s (" tbl-user-account)
       "id              serial      PRIMARY KEY, "
       "name            text        NULL, "
       "email           text        NULL, "
       "username        text        NULL, "
       "hashed_password text        NOT NULL, "
       "created_at      timestamptz NOT NULL, "
       "verified_at     timestamptz NULL, "
       "updated_at      timestamptz NOT NULL, "
       (format "%s      integer     NOT NULL, " col-updated-count)
       "deleted_at      timestamptz NULL)"))

(def v0-add-unique-constraint-user-account-email
  (format "ALTER TABLE %s ADD CONSTRAINT %s UNIQUE (email)"
          tbl-user-account
          constr-user-account-uniq-email))

(def v0-add-unique-constraint-user-account-username
  (format "ALTER TABLE %s ADD CONSTRAINT %s UNIQUE (username)"
          tbl-user-account
          constr-user-account-uniq-username))

(def v0-create-updated-count-inc-trigger-fn
  (fn [db-spec]
    (jcore/auto-inc-trigger-fn db-spec tbl-user-account col-updated-count)))

(def v0-create-user-account-updated-count-trigger-fn
  (fn [db-spec]
    (jcore/auto-inc-trigger db-spec
                            tbl-user-account
                            col-updated-count
                            (jcore/inc-trigger-fn-name tbl-user-account
                                                       col-updated-count))))

(def v0-create-authentication-token-ddl
  (str (format "CREATE TABLE IF NOT EXISTS %s (" tbl-auth-token)
       "id                       serial      PRIMARY KEY, "
       (format "user_id          integer     NOT NULL REFERENCES %s (id), " tbl-user-account)
       "hashed_token             text        UNIQUE NOT NULL, "
       "created_at               timestamptz NOT NULL, "
       "invalidated_at           timestamptz NULL, "
       "invalidated_reason       integer     NULL, "
       "expires_at               timestamptz NULL)"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Version 1 DDL vars
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def v1-user-add-deleted-reason-col
  (format "ALTER TABLE %s ADD COLUMN deleted_reason integer NULL", tbl-user-account))

(def v1-user-add-suspended-at-col
  (format "ALTER TABLE %s ADD COLUMN %s timestamptz NULL"
          tbl-user-account
          col-suspended-at))

(def v1-user-add-suspended-count-col
  (format "ALTER TABLE %s ADD COLUMN %s integer NOT NULL"
          tbl-user-account
          col-suspended-count))

(def v1-user-add-suspended-reason-col
  (format "ALTER TABLE %s ADD COLUMN suspended_reason integer NULL", tbl-user-account))

(def v1-create-suspended-count-inc-trigger-fn
  (fn [db-spec]
    (jcore/auto-inc-trigger-single-non-nil-cond-fn db-spec
                                                   tbl-user-account
                                                   col-suspended-count
                                                   col-suspended-at)))

(def v1-create-user-account-suspended-count-trigger-fn
  (fn [db-spec]
    (jcore/auto-inc-trigger db-spec
                            tbl-user-account
                            col-suspended-count
                            (jcore/inc-trigger-fn-name tbl-user-account
                                                       col-suspended-count))))
