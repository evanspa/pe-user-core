(ns pe-user-core.core
  (:require [pe-user-core.validation :as val]
            [pe-core-utils.core :as ucore]
            [pe-jdbc-utils.core :as jcore]
            [clojure.tools.logging :as log]
            [clojure.java.jdbc :as j]
            [pe-user-core.ddl :as uddl]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [cemerick.friend.credentials :refer [hash-bcrypt bcrypt-verify]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn next-user-account-id
  [db-spec]
  (jcore/seq-next-val db-spec "user_account_id_seq"))

(defn next-auth-token-id
  [db-spec]
  (jcore/seq-next-val db-spec "authentication_token_id_seq"))

(defn rs->user
  [user-rs]
  (let [from-sql-time-fn #(c/from-sql-time %)]
    [(:id user-rs) (-> user-rs
                       (ucore/replace-if-contains :name :user/name)
                       (ucore/replace-if-contains :email :user/email)
                       (ucore/replace-if-contains :username :user/username)
                       (ucore/replace-if-contains :id :user/id)
                       (ucore/replace-if-contains :updated_count :user/updated-count)
                       (ucore/replace-if-contains :hashed_password :user/hashed-password)
                       (ucore/replace-if-contains :updated_at :user/updated-at from-sql-time-fn)
                       (ucore/replace-if-contains :deleted_at :user/deleted-at from-sql-time-fn)
                       (ucore/replace-if-contains :verified_at :user/verified-at from-sql-time-fn)
                       (ucore/replace-if-contains :created_at :user/created-at from-sql-time-fn))]))

(defn get-schema-version
  [db-spec]
  (let [rs (j/query db-spec
                    [(format "select schema_version from %s" uddl/tbl-schema-version)]
                    :result-set-fn first)]
    (when rs
      (:schema_version rs))))

(defn set-schema-version
  [db-spec schema-version]
  (j/with-db-transaction [conn db-spec]
    (j/delete! conn :schema_version [])
    (j/insert! conn :schema_version {:schema_version schema-version})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Saving a user
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn save-new-user
  [db-spec new-id user]
  (let [validation-mask (val/save-new-user-validation-mask user)]
    (if (pos? (bit-and validation-mask val/su-any-issues))
      (throw (IllegalArgumentException. (str validation-mask)))
      (let [password (:user/password user)
            created-at (t/now)
            created-at-sql (c/to-timestamp created-at)
            hashed-password (hash-bcrypt (:user/password user))]
        (try
          (j/insert! db-spec
                     :user_account
                     {:id new-id
                      :name (:user/name user)
                      :email (:user/email user)
                      :username (:user/username user)
                      :created_at created-at-sql
                      :updated_at created-at-sql
                      :updated_count 1
                      :hashed_password hashed-password})
          (-> user
              (assoc :user/created-at created-at)
              (assoc :user/updated-at created-at)
              (assoc :user/hashed-password hashed-password))
          (catch java.sql.SQLException e
            (if (jcore/uniq-constraint-violated? db-spec e)
              (let [ucv (jcore/uniq-constraint-violated db-spec e)]
                (if (= ucv uddl/constr-user-account-uniq-email)
                  (throw (IllegalArgumentException. (str (bit-or 0
                                                                 val/su-email-already-registered
                                                                 val/su-any-issues))))
                  (if (= ucv uddl/constr-user-account-uniq-username)
                    (throw (IllegalArgumentException. (str (bit-or 0
                                                                   val/su-username-already-registered
                                                                   val/su-any-issues))))
                    (throw e))))
              (throw e))))))))

(defn save-user
  ([db-spec id user]
   (save-user db-spec id nil user))
  ([db-spec id auth-token-id user]
   (let [validation-mask (val/save-user-validation-mask user)]
     (if (pos? (bit-and validation-mask val/su-any-issues))
       (throw (IllegalArgumentException. (str validation-mask)))
       (let [password (:user/password user)
             updated-at (t/now)
             updated-at-sql (c/to-timestamp updated-at)]
         (j/update! db-spec
                    :user_account
                    (-> {:updated_at updated-at-sql}
                        (ucore/assoc-if-contains user :user/name :name)
                        (ucore/assoc-if-contains user :user/email :email)
                        (ucore/assoc-if-contains user :user/username :username)
                        (ucore/assoc-if-contains user :user/password :hashed_password hash-bcrypt))
                    ["id = ?" id])
         (-> user
             (assoc :user/updated-at updated-at)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Loading a user
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn load-user-by-email
  "Loads and returns a user entity given the user's email address.  Returns
   nil if no user is found."
  [db-spec email]
  {:pre [(and (not (nil? email))
              (not (empty? email)))]}
  (let [user-rs (j/query db-spec
                         [(format "SELECT * FROM %s WHERE email = ?" uddl/tbl-user-account) email]
                         :result-set-fn first)]
    (when user-rs
      (rs->user user-rs))))

(defn load-user-by-username
  "Loads and returns a user entity given the user's username.  Returns nil if no
  user is found."
  [db-spec username]
  {:pre [(and (not (nil? username))
              (not (empty? username)))]}
  (let [user-rs (j/query db-spec
                         [(format "SELECT * FROM %s WHERE username = ?" uddl/tbl-user-account) username]
                         :result-set-fn first)]
    (when user-rs
      (rs->user user-rs))))

(defn load-user-by-id
  "Loads and returns a user entity given the user's id.  Returns nil if no user
  is found."
  [db-spec id]
  {:pre [(not (nil? id))]}
  (let [user-rs (j/query db-spec
                         [(format "SELECT * FROM %s WHERE id = ?" uddl/tbl-user-account) id]
                         :result-set-fn first)]
    (when user-rs
      (rs->user user-rs))))

(defn load-user-by-authtoken
  "Loads and returns a user entity given an authentication token.  Returns
  nil if no associated user is found."
  [db-spec user-id plaintext-authtoken]
  {:pre [(not (nil? plaintext-authtoken))]}
  (let [tokens-rs (j/query db-spec
                           [(format (str "SELECT hashed_token "
                                         "FROM %s "
                                         "WHERE user_id = ? AND "
                                         "invalidated_at IS NULL AND "
                                         "(expires_at IS NULL OR expires_at > ?) "
                                         "ORDER BY created_at DESC")
                                    uddl/tbl-auth-token)
                            user-id
                            (c/to-timestamp (t/now))]
                           :row-fn :hashed_token)]
    (when (some #(bcrypt-verify plaintext-authtoken %) tokens-rs)
      (load-user-by-id db-spec user-id))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Authentication-related
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def invalrsn-logout                0)
(def invalrsn-account-closed        1)
(def invalrsn-account-suspended     2)
(def invalrsn-admin-individual      3)
(def invalrsn-admin-mass-comp-event 4)

(defn create-and-save-auth-token
  ([db-spec user-id new-id]
   (create-and-save-auth-token db-spec
                               user-id
                               new-id
                               nil))
  ([db-spec user-id new-id exp-date]
   (let [uuid (str (java.util.UUID/randomUUID))]
     (j/insert! db-spec
                :authentication_token
                {:id new-id
                 :user_id user-id
                 :hashed_token (hash-bcrypt uuid)
                 :created_at (c/to-timestamp (t/now))
                 :expires_at (c/to-timestamp exp-date)})
     uuid)))

(defn load-authtokens-by-user-id
  [db-spec user-id]
  (j/query db-spec
           [(format "select * from %s where user_id = ?" uddl/tbl-auth-token)
            user-id]))

(defn load-authtoken-by-plaintext-token
  [db-spec user-id plaintext-token]
  (let [tokens (load-authtokens-by-user-id db-spec user-id)]
    (some #(when (bcrypt-verify plaintext-token (:hashed_token %)) %) tokens)))

(defn invalidate-user-token
  [db-spec user-id plaintext-token reason]
  (let [authtoken (load-authtoken-by-plaintext-token db-spec
                                                     user-id
                                                     plaintext-token)]
    (when authtoken
      (j/update! db-spec
                 :authentication_token
                 {:invalidated_at (c/to-timestamp (t/now))
                  :invalidated_reason reason}
                 ["id = ?" (:id authtoken)]))))

(defn invalidate-user-tokens
  [db-spec user-id reason]
  (j/update! db-spec
             :authentication_token
             {:invalidated_at (c/to-timestamp (t/now))
              :invalidated_reason reason}
             ["user_id = ?" user-id]))

(defn invalidate-all-tokens
  [db-spec reason]
  (j/update! db-spec
             :authentication_token
             {:invalidated_at (c/to-timestamp (t/now))
              :invalidated_reason reason}))

(defn logout-user-token
  [db-spec user-id plaintext-token]
  (invalidate-user-token db-spec user-id plaintext-token invalrsn-logout))

(def authenticate-user-by-authtoken load-user-by-authtoken)

(defmulti authenticate-user-by-password
  "Authenticates a user given an email address (or username) and password.  Upon
   a successful authentication, returns the associated user entity; otherwise
   returns nil."
  (fn [db-spec username-or-email plaintext-password]
    {:pre [(and (not (nil? db-spec))
                (not (empty? username-or-email))
                (not (empty? plaintext-password)))]}
    (if (.contains username-or-email "@")
      :email
      :username)))

(defmethod authenticate-user-by-password :email
  [db-spec email plaintext-password]
  (let [[_ user :as result] (load-user-by-email db-spec email)]
    (when (and user
               (bcrypt-verify plaintext-password (:user/hashed-password user)))
      result)))

(defmethod authenticate-user-by-password :username
  [db-spec username plaintext-password]
  (let [[_ user :as result] (load-user-by-username db-spec username)]
    (when (and user
               (bcrypt-verify plaintext-password (:user/hashed-password user)))
      result)))
