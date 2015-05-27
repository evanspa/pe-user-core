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
(use-fixtures :each (fn [f]
                      (jcore/drop-database db-spec-without-db db-name)
                      (jcore/create-database db-spec-without-db db-name)
                      (j/db-do-commands db-spec
                                        true
                                        uddl/schema-version-ddl
                                        uddl/v0-create-user-account-ddl
                                        uddl/v0-add-unique-constraint-user-account-email
                                        uddl/v0-add-unique-constraint-user-account-username
                                        uddl/v0-create-authentication-token-ddl)
                      (jcore/with-try-catch-exec-as-query db-spec
                        (uddl/v0-create-updated-count-inc-trigger-function-fn db-spec))
                      (jcore/with-try-catch-exec-as-query db-spec
                        (uddl/v0-create-user-account-updated-count-trigger-fn db-spec))
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
              new-id2 (core/next-user-account-id conn)
              t1 (t/now)
              t2 (t/now)]
          (core/save-new-user conn
                              new-id
                              {:user/username "smithj"
                               :user/email "smithj@test.com"
                               :user/name "John Smith"
                               :user/created-at t1
                               :user/password "insecure"})
          (core/save-new-user conn
                              new-id2
                              {:user/username "paulevans"
                               :user/email "p@p.com"
                               :user/name "Paul Evans"
                               :user/created-at t2
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
            (is (= t2 (:user/created-at user)))
            (is (= t2 (:user/updated-at user))))
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
            (is (= t1 (:user/created-at user)))
            (is (= t1 (:user/updated-at user)))
            (core/save-user conn
                            new-id
                            (-> user
                                (dissoc :user/hashed-password)
                                (dissoc :user/created-at)
                                (assoc :user/updated-at t2)
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
              (is (= t1 (:user/created-at user)))
              (is (= t2 (:user/updated-at user))))
            (let [new-token-id (core/next-auth-token-id conn)
                  [user-id user] (core/load-user-by-id conn new-id)
                  t3 (t/now)]
              (core/create-and-save-auth-token conn new-id new-token-id)
              (core/save-user conn
                              new-id
                              new-token-id
                              (-> user
                                  (dissoc :user/hashed-password)
                                  (dissoc :user/created-at)
                                  (assoc :user/updated-at t3)
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
                (is (= t1 (:user/created-at user)))
                (is (= t3 (:user/updated-at user))))))
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
            (is (= t2 (:user/created-at user)))
            (is (= t2 (:user/updated-at user)))))))
    (testing "Attempting to save a new user with a duplicate username"
      (j/with-db-transaction [conn db-spec]
        (let [new-id (core/next-user-account-id conn)]
          (try
            (core/save-new-user conn
                                new-id
                                {:user/username "smithj2"
                                 :user/email "smithj@test.biz"
                                 :user/name "John Smith"
                                 :user/created-at (t/now)
                                 :user/password "insecure"})
            (is false "Should not have reached this")
            (catch IllegalArgumentException e
              (let [msg-mask (Long/parseLong (.getMessage e))]
                (is (pos? (bit-and msg-mask val/snu-any-issues)))
                (is (pos? (bit-and msg-mask val/snu-username-already-registered)))
                (is (zero? (bit-and msg-mask val/snu-invalid-email)))
                (is (zero? (bit-and msg-mask val/snu-created-at-not-provided)))
                (is (zero? (bit-and msg-mask val/snu-password-not-provided)))
                (is (zero? (bit-and msg-mask val/snu-email-already-registered)))))))))
    (testing "Attempting to save a new user with a duplicate email address"
      (j/with-db-transaction [conn db-spec]
        (let [new-id (core/next-user-account-id conn)]
          (try
            (core/save-new-user conn
                                new-id
                                {:user/username "smithjohn"
                                 :user/email "smithj@test.net"
                                 :user/name "John Smith"
                                 :user/created-at (t/now)
                                 :user/password "insecure"})
            (is false "Should not have reached this")
            (catch IllegalArgumentException e
              (let [msg-mask (Long/parseLong (.getMessage e))]
                (is (pos? (bit-and msg-mask val/snu-any-issues)))
                (is (pos? (bit-and msg-mask val/snu-email-already-registered)))
                (is (zero? (bit-and msg-mask val/snu-invalid-email)))
                (is (zero? (bit-and msg-mask val/snu-created-at-not-provided)))
                (is (zero? (bit-and msg-mask val/snu-password-not-provided)))
                (is (zero? (bit-and msg-mask val/snu-username-already-registered)))))))))
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
                             :user/created-at t1
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
            (is (= t1 (:user/created-at user)))
            (is (= t1 (:user/updated-at user))))
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
                             :user/created-at t1
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
          (is (= t1 (:user/created-at user)))
          (is (= t1 (:user/updated-at user))))
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
                             :user/created-at t1
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
          (is (= t1 (:user/created-at user)))
          (is (= t1 (:user/updated-at user))))
        (let [result (core/authenticate-user-by-password conn "smithj3" "wrong")]
          (is (nil? result))))))
  (testing "Authentication by password with nil inputs"
    (is (thrown? AssertionError (core/authenticate-user-by-password nil nil nil)))
    (is (thrown? AssertionError (core/authenticate-user-by-password db-spec nil nil)))))

(deftest Authenticating-Users-Multiple-Existing-Tokens
  (let [new-id (core/next-user-account-id db-spec)
        new-token-id (core/next-auth-token-id db-spec)
        t1 (t/now)]
    (j/with-db-transaction [conn db-spec]
      (core/save-new-user conn
                          new-id
                          {:user/username "smithj"
                           :user/email "smithj@test.com"
                           :user/name "John Smith"
                           :user/created-at t1
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
          (is (= t1 (:user/created-at user)))
          (is (= t1 (:user/updated-at user))))))))
