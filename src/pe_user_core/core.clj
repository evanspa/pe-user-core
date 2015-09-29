(ns pe-user-core.core
  (:require [pe-user-core.validation :as val]
            [pe-core-utils.core :as ucore]
            [pe-jdbc-utils.core :as jcore]
            [clojure.tools.logging :as log]
            [clojure.java.jdbc :as j]
            [pe-user-core.ddl :as uddl]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [cemerick.friend.credentials :refer [hash-bcrypt bcrypt-verify]]
            [clojurewerkz.mailer.core :refer [with-settings
                                              with-delivery-mode
                                              with-defaults
                                              with-settings
                                              build-email
                                              deliver-email]]))

(declare check-account-deleted)
(declare check-account-suspended)
(declare load-verificationtoken-by-plaintext-token)
(declare create-and-save-verification-token)

(def ^:dynamic *smtp-server-host* nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn next-user-account-id
  [db-spec]
  (jcore/seq-next-val db-spec "user_account_id_seq"))

(defn next-auth-token-id
  [db-spec]
  (jcore/seq-next-val db-spec "authentication_token_id_seq"))

(defn next-verification-token-id
  [db-spec]
  (jcore/seq-next-val db-spec "account_verification_token_id_seq"))

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
                       (ucore/replace-if-contains :deleted_reason :user/deleted-reason)
                       (ucore/replace-if-contains :verified_at :user/verified-at from-sql-time-fn)
                       (ucore/replace-if-contains :created_at :user/created-at from-sql-time-fn)
                       (ucore/replace-if-contains :suspended_at :user/suspended-at from-sql-time-fn)
                       (ucore/replace-if-contains :suspended_count :user/suspended-count)
                       (ucore/replace-if-contains :suspended_reason :user/suspended-reason))]))

(defn rs->verificationtoken
  [token-rs]
  (let [from-sql-time-fn #(c/from-sql-time %)]
    [(:id token-rs) (-> token-rs
                        (ucore/replace-if-contains :sent_to_email :veritoken/sent-to-email)
                        (ucore/replace-if-contains :id :veritoken/id)
                        (ucore/replace-if-contains :user_id :veritoken/user-id)
                        (ucore/replace-if-contains :hashed_token :veritoken/hashed-token)
                        (ucore/replace-if-contains :flagged_at :veritoken/flagged-at from-sql-time-fn)
                        (ucore/replace-if-contains :verified_at :veritoken/verified-at from-sql-time-fn)
                        (ucore/replace-if-contains :created_at :veritoken/created-at from-sql-time-fn)
                        (ucore/replace-if-contains :expires_at :veritoken/expires-at from-sql-time-fn))]))

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

(defn load-user-by-col
  [db-spec col col-val active-only]
  (jcore/load-entity-by-col db-spec uddl/tbl-user-account col "=" col-val rs->user active-only))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Loading a user
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn load-user-by-id-if-modified-since
  [db-spec user-id modified-since]
  (let [{users :entities}
        (jcore/entities-modified-since db-spec
                                       uddl/tbl-user-account
                                       "id"
                                       "="
                                       user-id
                                       "updated_at"
                                       "deleted_at"
                                       modified-since
                                       :user/id
                                       :user/deleted-at
                                       :user/updated-at
                                       rs->user)]
    (if (> (count users) 0)
      (first users)
      nil)))

(defn load-user-by-email
  "Loads and returns a user entity given the user's email address.  Returns
   nil if no user is found."
  ([db-spec email]
   (load-user-by-email db-spec email true))
  ([db-spec email active-only]
   (load-user-by-col db-spec "email" email active-only)))

(defn load-user-by-username
  "Loads and returns a user entity given the user's username.  Returns nil if no
  user is found."
  ([db-spec username]
   (load-user-by-username db-spec username true))
  ([db-spec username active-only]
   (load-user-by-col db-spec "username" username active-only)))

(defn load-user-by-id
  "Loads and returns a user entity given the user's id.  Returns nil if no user
  is found."
  ([db-spec id]
   (load-user-by-id db-spec id true))
  ([db-spec id active-only]
   (load-user-by-col db-spec "id" id active-only)))

(defn load-user-by-authtoken
  "Loads and returns a user entity given an authentication token.  Returns
  nil if no associated user is found."
  ([db-spec user-id plaintext-authtoken]
   (load-user-by-authtoken db-spec user-id plaintext-authtoken true))
  ([db-spec user-id plaintext-authtoken active-only]
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
       (load-user-by-id db-spec user-id active-only)))))

(defn load-user-by-verificationtoken
  "Loads and returns a user entity given an verification token.  Returns
  nil if no associated user is found."
  ([db-spec user-id plaintext-verificationtoken]
   (load-user-by-verificationtoken db-spec user-id plaintext-verificationtoken true))
  ([db-spec user-id plaintext-verificationtoken active-only]
   {:pre [(not (nil? plaintext-verificationtoken))]}
   (let [tokens-rs (j/query db-spec
                            [(format (str "SELECT hashed_token "
                                          "FROM %s "
                                          "WHERE user_id = ? AND "
                                          "flagged_at IS NULL AND "
                                          "(expires_at IS NULL OR expires_at > ?) "
                                          "ORDER BY created_at DESC")
                                     uddl/tbl-account-verification-token)
                             user-id
                             (c/to-timestamp (t/now))]
                            :row-fn :hashed_token)]
     (when (some #(bcrypt-verify plaintext-verificationtoken %) tokens-rs)
       (load-user-by-id db-spec user-id active-only)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Saving a user
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def user-key-pairs
  [[:user/name :name]
   [:user/email :email]
   [:user/username :username]
   [:user/password :hashed_password hash-bcrypt]
   [:user/suspended-at :suspended_at c/to-timestamp]
   [:user/suspended-count :suspended_count 0]
   [:user/suspended-reason :suspended_reason]
   [:user/deleted-at :deleted_at c/to-timestamp]
   [:user/deleted-reason :deleted_reason]])

(def user-uniq-constraints
  [[uddl/constr-user-account-uniq-email val/su-email-already-registered]
   [uddl/constr-user-account-uniq-username val/su-username-already-registered]])

(defn save-new-user
  [db-spec new-id user]
  (jcore/save-new-entity db-spec
                         new-id
                         user
                         val/save-new-user-validation-mask
                         val/su-any-issues
                         load-user-by-id
                         :user_account
                         user-key-pairs
                         nil
                         :user/created-at
                         :user/updated-at
                         user-uniq-constraints
                         nil))

(defn send-verification-notice
  [db-spec
   user-id
   mustache-template
   subject-line
   from
   verification-url-maker-fn
   flagged-url-maker-fn]
  (let [[_ loaded-user :as loaded-user-result] (load-user-by-id db-spec user-id true)]
    (when loaded-user-result
      (when (and (nil? (:user/verified-at loaded-user))
                 (not (nil? (:user/email loaded-user))))
        (let [verification-token (create-and-save-verification-token db-spec
                                                                     user-id
                                                                     (next-verification-token-id db-spec)
                                                                     (:user/email loaded-user))]
          (when (not (nil? *smtp-server-host*))
            (with-settings {:host *smtp-server-host*}
              (with-delivery-mode :smtp
                (deliver-email {:from from,
                                :to [(:user/email loaded-user)]
                                :subject subject-line}
                               mustache-template
                               (merge {:verification-url (verification-url-maker-fn user-id
                                                                                    verification-token)
                                       :flagged-url (flagged-url-maker-fn user-id
                                                                          verification-token)}
                                      loaded-user)
                               :text/html)))))))))

(defn save-user
  ([db-spec id user]
   (save-user db-spec id nil user))
  ([db-spec id auth-token-id user]
   (save-user db-spec id auth-token-id user nil))
  ([db-spec id auth-token-id user if-unmodified-since]
   (jcore/save-entity db-spec
                      id
                      user
                      val/save-user-validation-mask
                      val/su-any-issues
                      load-user-by-id
                      :user_account
                      user-key-pairs
                      :user/updated-at
                      user-uniq-constraints
                      nil
                      if-unmodified-since)))

(defn verify-user
  [db-spec user-id plaintext-verificationtoken]
  (let [[_ user :as user-result] (load-user-by-verificationtoken db-spec
                                                                 user-id
                                                                 plaintext-verificationtoken false)]
    (when user-result
      (let [user (-> user
                     (check-account-suspended)
                     (check-account-deleted))
            verificationtoken-rs (load-verificationtoken-by-plaintext-token db-spec
                                                                            user-id
                                                                            plaintext-verificationtoken)]
        (when verificationtoken-rs
          (when (and (nil? (:verified_at verificationtoken-rs))
                     (nil? (:flagged_at verificationtoken-rs)))
            (let [now-sql (c/to-timestamp (t/now))]
              (j/update! db-spec
                         :account_verification_token
                         {:verified_at now-sql}
                         ["id = ?" (:id verificationtoken-rs)])
              (j/update! db-spec
                         :user_account
                         {:verified_at now-sql
                          :updated_at now-sql}
                         ["id = ?" user-id]))))
        user))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Authentication-related
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def invalrsn-logout                                  0)
(def invalrsn-account-closed                          1)
(def invalrsn-account-suspended                       2)
(def invalrsn-admin-individual                        3)
(def invalrsn-admin-mass-comp-event                   4)
(def invalrsn-testing                                 5)
(def invalrsn-account-flagged-from-verification-email 6)

(def loginfailrsn-account-deleted   0)
(def loginfailrsn-account-suspended 1)

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

(defn create-and-save-verification-token
  ([db-spec user-id new-id email-address]
   (let [uuid (str (java.util.UUID/randomUUID))
         now (t/now)
         now-sql (c/to-timestamp now)
         expires-at nil ;(t/plus now (t/weeks 2))
         expires-at-sql (c/to-timestamp expires-at)]
     (j/insert! db-spec
                :account_verification_token
                {:id new-id
                 :user_id user-id
                 :sent_to_email email-address
                 :hashed_token (hash-bcrypt uuid)
                 :created_at now-sql
                 :expires_at expires-at-sql})
     uuid)))

(defn load-authtokens-by-user-id
  [db-spec user-id]
  (j/query db-spec
           [(format "select * from %s where user_id = ?" uddl/tbl-auth-token)
            user-id]))

(defn load-verificationtokens-by-user-id
  [db-spec user-id]
  (j/query db-spec
           [(format "select * from %s where user_id = ?" uddl/tbl-account-verification-token)
            user-id]))

(defn load-authtoken-by-plaintext-token
  [db-spec user-id plaintext-token]
  (let [tokens (load-authtokens-by-user-id db-spec user-id)]
    (some #(when (bcrypt-verify plaintext-token (:hashed_token %)) %) tokens)))

(defn load-verificationtoken-by-plaintext-token
  [db-spec user-id plaintext-token]
  (let [tokens (load-verificationtokens-by-user-id db-spec user-id)]
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
              :invalidated_reason reason}
             nil))

(defn logout-user-token
  [db-spec user-id plaintext-token]
  (invalidate-user-token db-spec user-id plaintext-token invalrsn-logout))

(defn check-account-suspended
  [user]
  (if (:user/suspended-at user)
    (throw (ex-info nil {:cause :account-is-suspended}))
    user))

(defn check-account-deleted
  [user]
  (if (:user/deleted-at user)
    (throw (ex-info nil {:cause :account-is-deleted}))
    user))

(defn authenticate-user-by-authtoken
  [db-spec user-id plaintext-authtoken]
  (let [[_ user :as result] (load-user-by-authtoken db-spec user-id plaintext-authtoken false)]
    (when result
      (-> user
          (check-account-suspended)
          (check-account-deleted))
      result)))

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
  (let [[_ user :as result] (load-user-by-email db-spec email false)]
    (when (and user
               (bcrypt-verify plaintext-password (:user/hashed-password user)))
      (-> user
          (check-account-suspended)
          (check-account-deleted))
      result)))

(defmethod authenticate-user-by-password :username
  [db-spec username plaintext-password]
  (let [[_ user :as result] (load-user-by-username db-spec username false)]
    (when (and user
               (bcrypt-verify plaintext-password (:user/hashed-password user)))
      (-> user
          (check-account-suspended)
          (check-account-deleted))
      result)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Suspending a user account
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn suspend-user
  [db-spec user-id reason if-unmodified-since]
  (let [loaded-user-result
        (jcore/save-rawmap db-spec
                           user-id
                           {uddl/col-suspended-at (c/to-timestamp (t/now))
                            "suspended_reason" reason}
                           val/su-any-issues
                           load-user-by-id
                           :user_account
                           :user/updated-at
                           nil
                           if-unmodified-since)]
    (invalidate-user-tokens db-spec user-id invalrsn-account-suspended)
    loaded-user-result))

(defn unsuspend-user
  [db-spec user-id if-unmodified-since]
  (jcore/save-rawmap db-spec
                     user-id
                     {uddl/col-suspended-at nil
                      "suspended_reason" nil}
                     val/su-any-issues
                     load-user-by-id
                     :user_account
                     :user/updated-at
                     nil
                     if-unmodified-since))

(def sususeracctrsn-nonpayment                               0)
(def sususeracctrsn-testing                                  1)
(def sususeracctrsn-flagged-from-verification-email          2)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Closing / deleting a user account
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
 (defn flag-verification-token
  [db-spec user-id plaintext-token]
  (let [token (load-verificationtoken-by-plaintext-token db-spec
                                                         user-id
                                                         plaintext-token)]
    (when token
      (do
        (j/update! db-spec
                   :account_verification_token
                   {:flagged_at (c/to-timestamp (t/now))}
                   ["id = ?" (:id token)])
        (invalidate-user-tokens db-spec
                                user-id
                                invalrsn-account-flagged-from-verification-email)
        (suspend-user db-spec user-id sususeracctrsn-flagged-from-verification-email nil)))))

(defn mark-user-as-deleted
  [db-spec user-id reason if-unmodified-since]
  (let [loaded-user-result
        (jcore/mark-entity-as-deleted db-spec
                                      user-id
                                      load-user-by-id
                                      :user_account
                                      :user/updated-at
                                      if-unmodified-since
                                      {"deleted_reason" reason})]
    (invalidate-user-tokens db-spec user-id invalrsn-account-closed)
    loaded-user-result))

(defn undelete-user
  [db-spec user-id if-unmodified-since]
  (jcore/save-rawmap db-spec
                     user-id
                     {"deleted_at" nil
                      "deleted_reason" nil}
                     val/su-any-issues
                     (fn [db-spec user-id] (load-user-by-id db-spec user-id false))
                     :user_account
                     :user/updated-at
                     nil
                     if-unmodified-since))

(def deluseracctrsn-userinitiated 0)
(def deluseracctrsn-testing       1)
