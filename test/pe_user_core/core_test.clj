(ns pe-user-core.core-test
  (:require [clojure.test :refer :all]
            [clojure.java.jdbc :as j]
            [pe-jdbc-utils.core :as jcore]
            [pe-user-core.core :as core]
            [pe-user-core.ddl :as uddl]
            [pe-user-core.validation :as val]
            [pe-user-core.test-utils :refer [db-spec-without-db
                                             db-spec
                                             db-name]]
            [pe-core-utils.core :as ucore]
            [clojure.tools.logging :as log]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [cemerick.friend.credentials :refer [hash-bcrypt bcrypt-verify]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixtures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(use-fixtures :each
  (fn [f]
    (jcore/drop-database db-spec-without-db db-name)
    (jcore/create-database db-spec-without-db db-name)
    (j/db-do-commands db-spec
                      true
                      uddl/schema-version-ddl
                      uddl/v0-create-user-account-ddl
                      uddl/v0-add-unique-constraint-user-account-email
                      uddl/v0-add-unique-constraint-user-account-username
                      uddl/v0-create-authentication-token-ddl
                      uddl/v1-user-add-deleted-reason-col
                      uddl/v1-user-add-suspended-at-col
                      uddl/v1-user-add-suspended-reason-col
                      uddl/v1-user-add-suspended-count-col
                      uddl/v2-create-email-verification-token-ddl
                      uddl/v3-create-password-reset-token-ddl)
    (jcore/with-try-catch-exec-as-query db-spec
      (uddl/v0-create-updated-count-inc-trigger-fn db-spec))
    (jcore/with-try-catch-exec-as-query db-spec
      (uddl/v0-create-user-account-updated-count-trigger-fn db-spec))
    (jcore/with-try-catch-exec-as-query db-spec
      (uddl/v1-create-suspended-count-inc-trigger-fn db-spec))
    (jcore/with-try-catch-exec-as-query db-spec
      (uddl/v1-create-user-account-suspended-count-trigger-fn db-spec))
    (f)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest Loading-and-Saving-Users
  (testing "Saving (and then loading) a new user"
    (is (nil? (core/load-user-by-username db-spec "smithj"))
        "Lookup of non-existent user by username should yield nil")
    (is (nil? (core/load-user-by-email db-spec "smithj@test.com"))
        "Lookup of non-existent user by email should yield nil")
    (testing "Updating (and then re-loading) the user"
      (j/with-db-transaction [conn db-spec]
        (let [new-id (core/next-user-account-id conn)
              new-id2 (core/next-user-account-id conn)]
          (core/save-new-user conn
                              new-id
                              {:user/username "smithj"
                               :user/email "smithj@test.com"
                               :user/name "John Smith"
                               :user/password "insecure"})
          (core/save-new-user conn
                              new-id2
                              {:user/username "paulevans"
                               :user/email "p@p.com"
                               :user/name "Paul Evans"
                               :user/password "insecure2"})
          (let [[user-id user] (core/load-user-by-id conn new-id2)]
            (is (not (nil? user-id)))
            (is (not (nil? user)))
            (is (= new-id2 user-id))
            (is (= "paulevans" (:user/username user)))
            (is (= "Paul Evans" (:user/name user)))
            (is (= new-id2 (:user/id user)))
            (is (= 1 (:user/updated-count user)))
            (is (= "p@p.com" (:user/email user)))
            (is (not (nil? (:user/hashed-password user))))
            (is (nil? (:user/deleted-at user)))
            (is (not (nil? (:user/created-at user))))
            (is (not (nil? (:user/updated-at user))))
            (is (nil? (:user/suspended-at user)))
            (is (nil? (:user/verified-at user)))
            (is (= 0 (:user/suspended-count user)))
            (is (nil? (:user/suspended-reason user))))
          (let [[user-id user] (core/load-user-by-id conn new-id)
                t2 (t/now)]
            (is (not (nil? user-id)))
            (is (not (nil? user)))
            (is (= new-id user-id))
            (is (= "smithj" (:user/username user)))
            (is (= "John Smith" (:user/name user)))
            (is (= new-id (:user/id user)))
            (is (= 1 (:user/updated-count user)))
            (is (= "smithj@test.com" (:user/email user)))
            (is (not (nil? (:user/hashed-password user))))
            (is (nil? (:user/deleted-at user)))
            (is (nil? (:user/verified-at user)))
            (is (not (nil? (:user/created-at user))))
            (is (not (nil? (:user/updated-at user))))
            (core/save-user conn
                            new-id
                            (-> user
                                (assoc :user/name "Johnny Smith")
                                (assoc :user/username "smithj2")
                                (assoc :user/password "insecure2")
                                (assoc :user/email "smithj@test.net")))
            (let [[user-id user] (core/load-user-by-id conn new-id)
                  new-id2 (core/next-user-account-id conn)]
              (is (not (nil? user-id)))
              (is (not (nil? user)))
              (is (= new-id user-id))
              (is (= "smithj2" (:user/username user)))
              (is (= "Johnny Smith" (:user/name user)))
              (is (= new-id (:user/id user)))
              (is (= 2 (:user/updated-count user)))
              (is (= "smithj@test.net" (:user/email user)))
              (is (not (nil? (:user/hashed-password user))))
              (is (nil? (:user/deleted-at user)))
              (is (nil? (:user/verified-at user)))
              (is (not (nil? (:user/created-at user))))
              (is (not (nil? (:user/updated-at user)))))
            (let [new-token-id (core/next-auth-token-id conn)
                  [user-id user] (core/load-user-by-id conn new-id)
                  t3 (t/now)]
              (core/create-and-save-auth-token conn new-id new-token-id)
              (core/save-user conn
                              new-id
                              new-token-id
                              (-> user
                                  (assoc :user/name "Johnny R. Smith")))
              (let [[user-id user] (core/load-user-by-id conn new-id)]
                (is (not (nil? user-id)))
                (is (not (nil? user)))
                (is (= new-id user-id))
                (is (= "Johnny R. Smith" (:user/name user)))
                (is (= new-id (:user/id user)))
                (is (= 3 (:user/updated-count user)))
                (is (not (nil? (:user/hashed-password user))))
                (is (nil? (:user/deleted-at user)))
                (is (not (nil? (:user/created-at user))))
                (is (not (nil? (:user/updated-at user)))))))
          ; just want to make sure the updated_count didn't get impacted
          ; when saving 'john smith' user
          (let [[user-id user] (core/load-user-by-id conn new-id2)]
            (is (not (nil? user-id)))
            (is (not (nil? user)))
            (is (= new-id2 user-id))
            (is (= "paulevans" (:user/username user)))
            (is (= "Paul Evans" (:user/name user)))
            (is (= new-id2 (:user/id user)))
            (is (= 1 (:user/updated-count user)))
            (is (= "p@p.com" (:user/email user)))
            (is (not (nil? (:user/hashed-password user))))
            (is (nil? (:user/deleted-at user)))
            (is (nil? (:user/flagged-at user)))
            (is (nil? (:user/suspended-at user)))
            (is (= 0 (:user/suspended-count user)))
            (is (nil? (:user/suspended-reason user)))
            (is (not (nil? (:user/created-at user))))
            (is (not (nil? (:user/updated-at user)))))
          (testing "Attempting to save existing user with invalid email"
            (try
              (core/save-user conn new-id2 {:user/email "paul"})
              (is false "Should not have reached this")
              (catch IllegalArgumentException e
                (let [msg-mask (Long/parseLong (.getMessage e))]
                  (is (pos? (bit-and msg-mask val/su-any-issues)))
                  (is (zero? (bit-and msg-mask val/su-username-and-email-not-provided)))
                  (is (zero? (bit-and msg-mask val/su-username-already-registered)))
                  (is (pos? (bit-and msg-mask val/su-invalid-email)))
                  (is (zero? (bit-and msg-mask val/su-password-not-provided)))
                  (is (zero? (bit-and msg-mask val/su-email-already-registered)))))))
          (testing "Attempting to save existing user with invalid if-unmodified-since value"
            (try
              (let [[_ user] (core/load-user-by-id conn new-id2)
                    if-unmodified-since-val (t/minus (:user/updated-at user) (t/weeks 1))]
                (core/save-user conn new-id2 nil {:user/name "paul"} if-unmodified-since-val)
                (is false "Should not have reached this"))
              (catch clojure.lang.ExceptionInfo e
                (let [type (-> e ex-data :type)
                      cause (-> e ex-data :cause)]
                  (is (= type :precondition-failed))
                  (is (= cause :unmodified-since-check-failed))))))
          (testing "Attempting to save existing user with valid if-unmodified-since value"
            (let [[_ user] (core/load-user-by-id conn new-id2)
                  current-updated-at-val (:user/updated-at user)
                  current-updated-count (:user/updated-count user)
                  if-unmodified-since-val (t/plus current-updated-at-val (t/weeks 1))]
              (core/save-user conn new-id2 nil {:user/name "Paul Evans"} if-unmodified-since-val)
              (let [[_ user] (core/load-user-by-id conn new-id2)]
                (is (= (:user/name user) "Paul Evans"))
                (is (= (inc current-updated-count) (:user/updated-count user)))
                (is (t/after? (:user/updated-at user) current-updated-at-val)))))
          (testing "Attempting to save existing user with invalid id"
            (try
              (core/save-user conn -99 nil {:user/name "Paul Evans"})
              (is false "Should not have reached this")
              (catch clojure.lang.ExceptionInfo e
                (let [cause (-> e ex-data :cause)]
                  (is (= cause :entity-not-found))))))
          (testing "Attempting to save existing user with empty email AND username"
            (try
              (core/save-user conn new-id2 {:user/email ""
                                            :user/username ""})
              (is false "Should not have reached this")
              (catch IllegalArgumentException e
                (let [msg-mask (Long/parseLong (.getMessage e))]
                  (is (pos? (bit-and msg-mask val/su-any-issues)))
                  (is (pos? (bit-and msg-mask val/su-username-and-email-not-provided)))
                  (is (zero? (bit-and msg-mask val/su-username-already-registered)))
                  (is (zero? (bit-and msg-mask val/su-invalid-email)))
                  (is (zero? (bit-and msg-mask val/su-password-not-provided)))
                  (is (zero? (bit-and msg-mask val/su-email-already-registered)))))))
          (testing "Attempting to save existing user with empty email"
            (core/save-user conn new-id2 {:user/email ""
                                          :user/username "evanspa"})
            (let [[user-id user] (core/load-user-by-id conn new-id2)]
              (is (not (nil? user-id)))
              (is (not (nil? user)))
              (is (= new-id2 user-id))
              (is (= "evanspa" (:user/username user)))
              (is (= "Paul Evans" (:user/name user)))
              (is (= new-id2 (:user/id user)))
              (is (= 3 (:user/updated-count user)))
              (is (= "" (:user/email user)))
              (is (not (nil? (:user/hashed-password user))))
              (is (nil? (:user/deleted-at user)))
              (is (nil? (:user/flagged-at user)))
              (is (not (nil? (:user/created-at user))))
              (is (not (nil? (:user/updated-at user))))))
          (testing "Attempting to save existing user with nil empty"
            (core/save-user conn new-id2 {:user/email nil
                                          :user/username "evanspa"})
            (let [[user-id user] (core/load-user-by-id conn new-id2)]
              (is (not (nil? user-id)))
              (is (not (nil? user)))
              (is (= new-id2 user-id))
              (is (= "evanspa" (:user/username user)))
              (is (= "Paul Evans" (:user/name user)))
              (is (= new-id2 (:user/id user)))
              (is (= 4 (:user/updated-count user)))
              (is (nil? (:user/email user)))
              (is (not (nil? (:user/hashed-password user))))
              (is (nil? (:user/deleted-at user)))
              (is (not (nil? (:user/created-at user))))
              (is (not (nil? (:user/updated-at user)))))))))
    (testing "Attempting to save a new user with a duplicate username"
      (j/with-db-transaction [conn db-spec]
        (let [new-id (core/next-user-account-id conn)]
          (try
            (core/save-new-user conn
                                new-id
                                {:user/username "smithj2"
                                 :user/email "smithj@test.biz"
                                 :user/name "John Smith"
                                 :user/password "insecure"})
            (is false "Should not have reached this")
            (catch IllegalArgumentException e
              (let [msg-mask (Long/parseLong (.getMessage e))]
                (is (pos? (bit-and msg-mask val/su-any-issues)))
                (is (pos? (bit-and msg-mask val/su-username-already-registered)))
                (is (zero? (bit-and msg-mask val/su-invalid-email)))
                (is (zero? (bit-and msg-mask val/su-password-not-provided)))
                (is (zero? (bit-and msg-mask val/su-email-already-registered)))))))))
    (testing "Attempting to save a new user with a duplicate email address"
      (j/with-db-transaction [conn db-spec]
        (let [new-id (core/next-user-account-id conn)]
          (try
            (core/save-new-user conn
                                new-id
                                {:user/username "smithjohn"
                                 :user/email "smithj@test.net"
                                 :user/name "John Smith"
                                 :user/password "insecure"})
            (is false "Should not have reached this")
            (catch IllegalArgumentException e
              (let [msg-mask (Long/parseLong (.getMessage e))]
                (is (pos? (bit-and msg-mask val/su-any-issues)))
                (is (pos? (bit-and msg-mask val/su-email-already-registered)))
                (is (zero? (bit-and msg-mask val/su-invalid-email)))
                (is (zero? (bit-and msg-mask val/su-password-not-provided)))
                (is (zero? (bit-and msg-mask val/su-username-already-registered)))))))))
    (testing "Attempt to load a non-existent user"
      (is (nil? (core/load-user-by-username db-spec "jacksonm")))
      (is (nil? (core/load-user-by-email db-spec "jacksonm@testing.com"))))
    (testing "Attempt to load a user with invalid inputs"
      (is (thrown? AssertionError (core/load-user-by-username db-spec "")))
      (is (thrown? AssertionError (core/load-user-by-username db-spec nil)))
      (is (thrown? AssertionError (core/load-user-by-email db-spec "")))
      (is (thrown? AssertionError (core/load-user-by-email db-spec nil))))))

(deftest Authenticating-Users
  (testing "Authenticate by authentication token"
    (let [new-id (core/next-user-account-id db-spec)
          new-token-id (core/next-auth-token-id db-spec)
          t1 (t/now)]
      (j/with-db-transaction [conn db-spec]
        (core/save-new-user conn
                            new-id
                            {:user/username "smithj"
                             :user/email "smithj@test.com"
                             :user/name "John Smith"
                             :user/password "insecure"})
        (let [plaintext-token (core/create-and-save-auth-token conn new-id new-token-id)]
          (is (and (not (nil? plaintext-token))
                   (not (empty? plaintext-token))))
          (let [[user-id user] (core/load-user-by-authtoken conn new-id plaintext-token)]
            (is (not (nil? user-id)))
            (is (not (nil? user)))
            (is (= new-id user-id))
            (is (= 1 (:user/updated-count user)))
            (is (= "smithj" (:user/username user)))
            (is (= "John Smith" (:user/name user)))
            (is (= new-id (:user/id user)))
            (is (= "smithj@test.com" (:user/email user)))
            (is (not (nil? (:user/hashed-password user))))
            (is (nil? (:user/deleted-at user)))
            (is (not (nil? (:user/created-at user))))
            (is (not (nil? (:user/updated-at user)))))
          (let [user-tokens (core/load-authtokens-by-user-id conn new-id)]
            (is (not (nil? user-tokens)))
            (is (= 1 (count user-tokens))))
          (let [authtoken (core/load-authtoken-by-plaintext-token conn new-id plaintext-token)]
            (is (not (nil? authtoken)))
            (is (= new-token-id (:id authtoken))))
          (let [ret (core/invalidate-user-token conn new-id plaintext-token core/invalrsn-logout)]
            (is (= 1 (first ret)))
            (let [token-rs (j/query conn
                                  [(format "SELECT * from %s where id = ?" uddl/tbl-auth-token) new-token-id]
                                  :result-set-fn first)]
              (is (not (nil? token-rs)))
              (is (= new-token-id (:id token-rs)))
              (is (not (nil? (:created_at token-rs))))
              (is (not (nil? (:user_id token-rs))))
              (is (not (nil? (:invalidated_at token-rs))))
              (is (not (nil? (:invalidated_reason token-rs))))
              (is (= core/invalrsn-logout (:invalidated_reason token-rs)))
              ; invalidated token should not usable to load a user
              (let [user-result (core/load-user-by-authtoken conn new-id plaintext-token)]
                (is (nil? user-result))))))
        (let [t1 (t/now)
              new-token-id (core/next-auth-token-id db-spec)
              plaintext-token (core/create-and-save-auth-token conn
                                                               new-id
                                                               new-token-id
                                                               t1)]
          (let [token-rs (j/query conn
                                  [(format "SELECT * from %s where id = ?" uddl/tbl-auth-token) new-token-id]
                                  :result-set-fn first)]
            (is (not (nil? token-rs)))
            (is (= new-token-id (:id token-rs)))
            (is (= new-id (:user_id token-rs)))
            (is (nil? (:invalidated_at token-rs)))
            (is (nil? (:invalidated_reason token-rs)))
            (is (= t1 (c/from-sql-time (:expires_at token-rs))))
            ; plaintext-token should be expired at this point
            (let [user-result (core/load-user-by-authtoken conn new-id plaintext-token)]
              (is (nil? user-result))))))))
  (testing "Authenticate by email and password"
    (let [new-id (core/next-user-account-id db-spec)
          t1 (t/now)]
      (j/with-db-transaction [conn db-spec]
        (core/save-new-user conn
                            new-id
                            {:user/username "smithj2"
                             :user/email "smithj@test.com2"
                             :user/name "John Smith2"
                             :user/password "insecure2"})
        (let [[user-id user] (core/authenticate-user-by-password conn "smithj@test.com2" "insecure2")]
          (is (not (nil? user-id)))
          (is (not (nil? user)))
          (is (= new-id user-id))
          (is (= "smithj2" (:user/username user)))
          (is (= "John Smith2" (:user/name user)))
          (is (= new-id (:user/id user)))
          (is (= 1 (:user/updated-count user)))
          (is (= "smithj@test.com2" (:user/email user)))
          (is (not (nil? (:user/hashed-password user))))
          (is (nil? (:user/deleted-at user)))
          (is (not (nil? (:user/created-at user))))
          (is (not (nil? (:user/updated-at user)))))
        (let [result (core/authenticate-user-by-password conn "smithj@test.com2" "wrong")]
          (is (nil? result))))))
  (testing "Authenticate by username and password"
    (let [new-id (core/next-user-account-id db-spec)
          t1 (t/now)]
      (j/with-db-transaction [conn db-spec]
        (core/save-new-user conn
                            new-id
                            {:user/username "smithj3"
                             :user/email "smithj@test.com3"
                             :user/name "John Smith3"
                             :user/password "insecure3"})
        (let [[user-id user] (core/authenticate-user-by-password conn "smithj3" "insecure3")]
          (is (not (nil? user-id)))
          (is (not (nil? user)))
          (is (= new-id user-id))
          (is (= "smithj3" (:user/username user)))
          (is (= "John Smith3" (:user/name user)))
          (is (= new-id (:user/id user)))
          (is (= "smithj@test.com3" (:user/email user)))
          (is (not (nil? (:user/hashed-password user))))
          (is (nil? (:user/deleted-at user)))
          (is (not (nil? (:user/created-at user))))
          (is (not (nil? (:user/updated-at user)))))
        (let [result (core/authenticate-user-by-password conn "smithj3" "wrong")]
          (is (nil? result))))))
  (testing "Authentication by password with nil inputs"
    (is (thrown? AssertionError (core/authenticate-user-by-password nil nil nil)))
    (is (thrown? AssertionError (core/authenticate-user-by-password db-spec nil nil)))))

(deftest Authenticating-Users-Multiple-Existing-Tokens
  (testing "Authenticating with multiple tokens present"
    (let [new-id (core/next-user-account-id db-spec)
          new-token-id (core/next-auth-token-id db-spec)
          t1 (t/now)]
      (j/with-db-transaction [conn db-spec]
        (core/save-new-user conn
                            new-id
                            {:user/username "smithj"
                             :user/email "smithj@test.com"
                             :user/name "John Smith"
                             :user/password "insecure"})
        (let [_ (core/create-and-save-auth-token conn new-id (core/next-auth-token-id db-spec))
              plaintext-token (core/create-and-save-auth-token conn new-id (core/next-auth-token-id db-spec))
              _ (core/create-and-save-auth-token conn new-id (core/next-auth-token-id db-spec))
              _ (core/create-and-save-auth-token conn new-id (core/next-auth-token-id db-spec))]
          (let [[user-id user] (core/load-user-by-authtoken conn new-id plaintext-token)]
            (is (not (nil? user-id)))
            (is (not (nil? user)))
            (is (= new-id user-id))
            (is (= "smithj" (:user/username user)))
            (is (= "John Smith" (:user/name user)))
            (is (= new-id (:user/id user)))
            (is (= "smithj@test.com" (:user/email user)))
            (is (not (nil? (:user/hashed-password user))))
            (is (nil? (:user/deleted-at user)))
            (is (not (nil? (:user/created-at user))))
            (is (not (nil? (:user/updated-at user))))))))))

(deftest Verifying-Users
  (testing "Verifying user accounts"
    (let [new-id (core/next-user-account-id db-spec)
          t1 (t/now)]
      (j/with-db-transaction [conn db-spec]
        (core/save-new-user conn
                            new-id
                            {:user/username "smithj"
                             :user/email "smithj@test.com"
                             :user/name "John Smith"
                             :user/password "insecure"})
        (let [plaintext-token (core/create-and-save-verification-token conn
                                                                       new-id
                                                                       "smithj@test.com")]
          (let [[user-id user] (core/load-user-by-verification-token conn "smithj@test.com" plaintext-token)]
            (is (not (nil? user-id)))
            (is (not (nil? user)))
            (is (= new-id user-id))
            (is (= "smithj" (:user/username user)))
            (is (= "John Smith" (:user/name user)))
            (is (= new-id (:user/id user)))
            (is (= "smithj@test.com" (:user/email user)))
            (is (not (nil? (:user/hashed-password user))))
            (is (nil? (:user/deleted-at user)))
            (is (nil? (:user/flagged-at user)))
            (is (nil? (:user/verified-at user)))
            (is (not (nil? (:user/created-at user))))
            (is (not (nil? (:user/updated-at user))))
            (is (= 0 (:user/suspended-count user)))
            (core/verify-user conn "smithj@test.com" plaintext-token)
            (let [[user-id user] (core/load-user-by-verification-token conn "smithj@test.com" plaintext-token)]
              (is (not (nil? user)))
              (is (not (nil? (:user/verified-at user)))))))))))

(deftest Password-Reset
  (testing "Password Reset"
    (let [new-id (core/next-user-account-id db-spec)
          t1 (t/now)]
      (j/with-db-transaction [conn db-spec]
        (core/save-new-user conn
                            new-id
                            {:user/username "smithj"
                             :user/email "smithj@test.com"
                             :user/name "John Smith"
                             :user/password "insecure"})
        (let [plaintext-token (core/create-and-save-password-reset-token conn
                                                                         new-id
                                                                         "smithj@test.com")]
          (let [[user-id user] (core/load-user-by-password-reset-token conn "smithj@test.com" plaintext-token)]
            (log/debug "in test, user-0: " user)
            (is (not (nil? user-id)))
            (is (not (nil? user)))
            (is (= new-id user-id))
            (is (= "smithj" (:user/username user)))
            (is (= "John Smith" (:user/name user)))
            (is (= new-id (:user/id user)))
            (is (= "smithj@test.com" (:user/email user)))
            (is (not (nil? (:user/hashed-password user))))
            (is (nil? (:user/deleted-at user)))
            (is (nil? (:user/flagged-at user)))
            (is (nil? (:user/verified-at user)))
            (is (not (nil? (:user/created-at user))))
            (is (not (nil? (:user/updated-at user))))
            (is (= 0 (:user/suspended-count user)))
            (core/prepare-password-reset conn "smithj@test.com" plaintext-token)
            (core/reset-password conn "smithj@test.com" plaintext-token "als01nsecure")
            (let [user-result (core/authenticate-user-by-password conn "smithj@test.com" "insecure")]
              (is (nil? user-result)))
            (let [[user-id user] (core/authenticate-user-by-password conn "smithj@test.com" "als01nsecure")]
              (is (not (nil? user)))
              (is (= new-id user-id))
              (is (= "smithj" (:user/username user)))
              (is (= "John Smith" (:user/name user)))
              (is (= new-id (:user/id user)))
              (is (= "smithj@test.com" (:user/email user)))
              (is (not (nil? (:user/hashed-password user)))))))))))

(deftest Flagging-Users
  (testing "Flagging user accounts"
    (let [new-id (core/next-user-account-id db-spec)
          t1 (t/now)]
      (j/with-db-transaction [conn db-spec]
        (core/save-new-user conn
                            new-id
                            {:user/username "smithj"
                             :user/email "smithj@test.com"
                             :user/name "John Smith"
                             :user/password "insecure"})
        ; create a bunch of auth tokens
        (core/create-and-save-auth-token conn new-id (core/next-auth-token-id db-spec))
        (core/create-and-save-auth-token conn new-id (core/next-auth-token-id db-spec))
        (core/create-and-save-auth-token conn new-id (core/next-auth-token-id db-spec))
        (core/create-and-save-auth-token conn new-id (core/next-auth-token-id db-spec))
        (let [plaintext-token (core/create-and-save-verification-token conn
                                                                       new-id
                                                                       "smithj@test.com")]
          (core/flag-verification-token conn new-id plaintext-token)
          (let [[user-id user :as flagged-user-result] (core/load-user-by-id conn new-id false)]
            (is (not (nil? flagged-user-result)))
            (is (not (nil? (:user/suspended-at user))))
            (is (= 1 (:user/suspended-count user)))
            (is (= core/sususeracctrsn-flagged-from-verification-email (:user/suspended-reason user)))))))))

(deftest Suspending-Users
  (testing "Suspending user accounts"
    (let [new-id (core/next-user-account-id db-spec)
          new-token-id (core/next-auth-token-id db-spec)
          t1 (t/now)]
      (j/with-db-transaction [conn db-spec]
        (core/save-new-user conn
                            new-id
                            {:user/username "smithj"
                             :user/email "smithj@test.com"
                             :user/name "John Smith"
                             :user/password "insecure"})
        (let [plaintext-token (core/create-and-save-auth-token conn new-id (core/next-auth-token-id db-spec))]
          (let [[user-id user] (core/load-user-by-authtoken conn new-id plaintext-token)]
            (is (not (nil? user-id)))
            (is (not (nil? user)))
            (is (= new-id user-id))
            (is (= "smithj" (:user/username user)))
            (is (= "John Smith" (:user/name user)))
            (is (= new-id (:user/id user)))
            (is (= "smithj@test.com" (:user/email user)))
            (is (not (nil? (:user/hashed-password user))))
            (is (nil? (:user/deleted-at user)))
            (is (not (nil? (:user/created-at user))))
            (is (not (nil? (:user/updated-at user))))
            (is (= 0 (:user/suspended-count user)))
            (let [[user-id user :as suspended-user-result] (core/suspend-user conn
                                                                              user-id
                                                                              core/sususeracctrsn-testing
                                                                              (:user/updated-at user))]
              (is (not (nil? suspended-user-result)))
              (is (not (nil? (:user/suspended-at user))))
              (is (= 1 (:user/suspended-count user)))
              (is (= core/sususeracctrsn-testing (:user/suspended-reason user)))
              ; suspending an already-suspended account doesn't do much
              (let [[user-id user :as suspended-user-result] (core/suspend-user conn
                                                                                user-id
                                                                                core/sususeracctrsn-nonpayment
                                                                                (:user/updated-at user))]
                (is (not (nil? suspended-user-result)))
                (is (not (nil? (:user/suspended-at user))))
                (is (= 1 (:user/suspended-count user)))
                (is (= core/sususeracctrsn-nonpayment (:user/suspended-reason user)))
                (let [[user-id user :as unsuspended-user-result] (core/unsuspend-user conn
                                                                                      user-id
                                                                                      (:user/updated-at user))]
                  (is (not (nil? unsuspended-user-result)))
                  (is (nil? (:user/suspended-at user)))
                  (is (= 1 (:user/suspended-count user)))
                  (is (nil? (:user/suspended-reason user)))
                  ; suspend it again to increment the suspend-count
                  (let [[user-id user :as suspended-user-result] (core/suspend-user conn
                                                                                    user-id
                                                                                    core/sususeracctrsn-testing
                                                                                    (:user/updated-at user))]
                    (is (not (nil? suspended-user-result)))
                    (is (not (nil? (:user/suspended-at user))))
                    (is (= 2 (:user/suspended-count user)))
                    (is (= core/sususeracctrsn-testing (:user/suspended-reason user)))))))))))))

(deftest Deleting-Users
  (testing "Deleting user accounts"
    (let [new-id (core/next-user-account-id db-spec)
          new-token-id (core/next-auth-token-id db-spec)
          t1 (t/now)]
      (j/with-db-transaction [conn db-spec]
        (core/save-new-user conn
                            new-id
                            {:user/username "smithj"
                             :user/email "smithj@test.com"
                             :user/name "John Smith"
                             :user/password "insecure"})
        (let [plaintext-token (core/create-and-save-auth-token conn new-id (core/next-auth-token-id db-spec))]
          (let [[user-id user] (core/load-user-by-authtoken conn new-id plaintext-token)]
            (is (not (nil? user-id)))
            (is (not (nil? user)))
            (is (= new-id user-id))
            (is (= "smithj" (:user/username user)))
            (is (= "John Smith" (:user/name user)))
            (is (= new-id (:user/id user)))
            (is (= "smithj@test.com" (:user/email user)))
            (is (not (nil? (:user/hashed-password user))))
            (is (nil? (:user/deleted-at user)))
            (is (not (nil? (:user/created-at user))))
            (is (not (nil? (:user/updated-at user))))
            (is (= 0 (:user/suspended-count user)))
            (core/mark-user-as-deleted conn
                                       user-id
                                       core/deluseracctrsn-testing
                                       (:user/updated-at user))
            (is (nil? (core/load-user-by-id conn user-id)))
            (is (nil? (core/load-user-by-id conn user-id true)))
            (let [[user-id user :as deleted-user-result] (core/load-user-by-id conn user-id false)]
              (is (not (nil? deleted-user-result)))
              (let [[user-id user :as undeleted-user-result] (core/undelete-user conn
                                                                                 user-id
                                                                                 (:user/updated-at user))]
                (is (not (nil? user-id)))
                (is (not (nil? user)))
                (is (= new-id user-id))
                (is (= "smithj" (:user/username user)))
                (is (= "John Smith" (:user/name user)))
                (is (= new-id (:user/id user)))
                (is (= "smithj@test.com" (:user/email user)))
                (is (not (nil? (:user/hashed-password user))))
                (is (nil? (:user/deleted-at user)))
                (is (not (nil? (:user/created-at user))))
                (is (not (nil? (:user/updated-at user))))
                (is (= 0 (:user/suspended-count user)))))
            (let [loaded-user-result (core/load-user-by-authtoken conn new-id plaintext-token)]
              ; because when user was marked as deleted, all his auth tokens
              ; were invalidated
              (is (nil? loaded-user-result)))
            (let [[user-id user :as user-result] (core/load-user-by-id conn user-id)]
              ; because we undeleted the user
              (is (not (nil? user-result))))))))))
