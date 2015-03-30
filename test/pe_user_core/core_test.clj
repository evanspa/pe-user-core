(ns pe-user-core.core-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer [pprint]]
            [pe-user-core.core :as core]
            [pe-user-core.validation :as val]
            [pe-datomic-utils.core :as ducore]
            [pe-datomic-testutils.core :as dtucore]
            [pe-user-core.test-utils :refer [user-schema-files
                                             db-uri
                                             user-partition]]
            [pe-core-utils.core :as ucore]
            [clojure.java.io :refer [resource]]
            [datomic.api :as d]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [clj-time.core :as t]
            [cemerick.friend.credentials :refer [hash-bcrypt bcrypt-verify]]))

(def conn (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixtures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(use-fixtures :each (dtucore/make-db-refresher-fixture-fn db-uri
                                                          conn
                                                          user-partition
                                                          user-schema-files))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest Loading-and-Saving-Users
  (testing "Saving (and then loading) a new user"
    (is (= (count (core/get-all-users @conn)) 0))
    (is (nil? (core/load-user-by-username @conn "smithj"))  ; yes, is redundant
        "Lookup of non-existent user by username should yield nil")
    (is (nil? (core/load-user-by-email @conn "smithj@test.com"))  ; yes, is redundant
        "Lookup of non-existent user by email should yield nil")
    (testing "Updating (and then re-loading) the user"
      (let [u-entid (save-new-user @conn {:user/username "smithj"
                                          :user/password "insecure"})]
        (is (not= u-entid nil)
            "The new user entity should have been saved successfully")
        (is (= (count (core/get-all-users @conn)) 1))
        (let [u-ent (d/entity (d/db @conn) u-entid)]
          (is (not= u-ent nil) "Entity should load successfully")
          (is (= (:user/username u-ent) "smithj"))
          (is (not (nil? (:user/hashed-password u-ent))))
          (is (nil? (:user/name u-ent)))
          (is (nil? (:user/email u-ent))))
        ; save the same user, this time with a 'name' attribute
        (let [u-entid2 (save-new-user @conn {:user/username "smithj"
                                             :user/name "John L. Smith"
                                             :user/email "smithj@test.com"})]
          (is (= u-entid u-entid2) "Same entity ID as before")
          (is (= (count (core/get-all-users @conn)) 1))
          (let [u-ent (d/entity (d/db @conn) u-entid2)]
            (is (= (:user/name u-ent) "John L. Smith"))
            (is (= (:user/email u-ent) "smithj@test.com"))))))
    (testing "Attempt to save user with invalid inputs"
      (try
        (save-new-user @conn {:user/username "evans@prq"})
        (is false "Should not have reached this")
        (catch IllegalArgumentException e
          (let [msg-mask (Long/parseLong (.getMessage e))]
            (is (pos? (bit-and msg-mask val/saveusr-any-issues)))
            (is (pos? (bit-and msg-mask val/saveusr-invalid-username)))
            (is (zero? (bit-and msg-mask val/saveusr-invalid-email)))
            (is (zero? (bit-and msg-mask val/saveusr-identifier-not-provided))))))
      (try
        (save-new-user @conn {})
        (is false "Should not have reached this")
        (catch IllegalArgumentException e
          (let [msg-mask (Long/parseLong (.getMessage e))]
            (is (pos? (bit-and msg-mask val/saveusr-any-issues)))
            (is (zero? (bit-and msg-mask val/saveusr-invalid-username)))
            (is (zero? (bit-and msg-mask val/saveusr-invalid-email)))
            (is (pos? (bit-and msg-mask val/saveusr-identifier-not-provided))))))
      (try
        (save-new-user @conn {:user/username "smithj"
                                   :user/email "smithj_test_com"})
        (is false "Should not have reached this")
        (catch IllegalArgumentException e
          (let [msg-mask (Long/parseLong (.getMessage e))]
            (is (pos? (bit-and msg-mask val/saveusr-any-issues)))
            (is (zero? (bit-and msg-mask val/saveusr-invalid-username)))
            (is (pos? (bit-and msg-mask val/saveusr-invalid-email)))
            (is (zero? (bit-and msg-mask val/saveusr-identifier-not-provided))))))
      (try
        (save-new-user @conn {:user/name "Paul"})
        (is false "Should not have reached this")
        (catch IllegalArgumentException e
          (let [msg-mask (Long/parseLong (.getMessage e))]
            (is (pos? (bit-and msg-mask val/saveusr-any-issues)))
            (is (zero? (bit-and msg-mask val/saveusr-invalid-username)))
            (is (zero? (bit-and msg-mask val/saveusr-invalid-email)))
            (is (pos? (bit-and msg-mask val/saveusr-identifier-not-provided)))))))
    (testing "Attempt to load a non-existent user"
      (is (nil? (core/load-user-by-username @conn "jacksonm")))
      (is (nil? (core/load-user-by-email @conn "jacksonm@testing.com"))))
    (testing "Attempt to load a user with invalid inputs"
      (is (thrown? AssertionError (core/load-user-by-username @conn "")))
      (is (thrown? AssertionError (core/load-user-by-username @conn nil)))
      (is (thrown? AssertionError (core/load-user-by-email @conn "")))
      (is (thrown? AssertionError (core/load-user-by-email @conn nil))))))

(deftest Authenticating-Users
  (testing "Authenticate (by auth token) with valid inputs"
    (let [u-entid (save-new-user @conn {:user/name "Paul Smith"
                                        :user/username "smithpa"
                                        :user/password "in53cur3"})
          [plaintext-token auth-entid] (save-new-authtoken @conn u-entid nil)
          _ (is (not (nil? auth-entid)))
          auth-ent (d/entity (d/db @conn) auth-entid)
          _ (is (not (nil? auth-ent)))
          hashed-auth-token (:authtoken/hashed-token auth-ent)
          _ (is (not (nil? hashed-auth-token)))
          [u-entid u-ent :as result] (core/load-user-by-authtoken @conn u-entid plaintext-token)]
      (is (not (nil? u-entid)))
      (is (not (nil? u-ent)))
      (is (= "Paul Smith" (:user/name u-ent)))
      (is (= "smithpa" (:user/username u-ent)))
      (is (not (nil? (:user/hashed-password u-ent))))))
  (testing "Authenticate (by password) with valid inputs"
    (let [u-entid (save-new-user @conn {:user/name "Paul Smith"
                                        :user/username "smithpa"
                                        :user/password "in53cur3"})
          u-ent (d/entity (d/db @conn) u-entid)
          [authd-u-entid authd-u-ent] (core/authenticate-user-by-password @conn "smithpa" "in53cur3")]
      (is (not (nil? authd-u-ent)))
      (is (= u-ent authd-u-ent))
      (is (= (nil? (core/authenticate-user-by-password @conn "smithpa" "in53cur3"))))
      (is (nil? (core/authenticate-user-by-password @conn "smithpa" "wrong"))) ; wrong password
      (is (nil? (core/authenticate-user-by-password @conn "griffenp" "in53cur3"))))) ; unknown user
  (testing "Authentication (by password) with invalid inputs"
    (is (thrown? AssertionError (core/authenticate-user-by-password nil nil nil)))
    (is (thrown? AssertionError (core/authenticate-user-by-password @conn nil nil)))))

(deftest Authenticating-Users-Multiple-Existing-Tokens
  (testing "Authenticate by token when multiple (valid) tokens exist"
    (let [u-entid (save-new-user @conn {:user/name "Paul Smith"
                                        :user/username "smithpa"
                                        :user/password "in53cur3"})
          [plaintext-token auth-entid] (save-new-authtoken @conn u-entid nil)
          _ (save-new-authtoken @conn u-entid nil)
          _ (is (not (nil? auth-entid)))
          auth-ent (d/entity (d/db @conn) auth-entid)
          _ (is (not (nil? auth-ent)))
          hashed-auth-token (:authtoken/hashed-token auth-ent)
          _ (is (not (nil? hashed-auth-token)))
          [u-entid u-ent :as result] (core/load-user-by-authtoken @conn u-entid plaintext-token)]
      (is (not (nil? u-entid)))
      (is (not (nil? u-ent)))
      (is (= "Paul Smith" (:user/name u-ent)))
      (is (= "smithpa" (:user/username u-ent)))
      (is (not (nil? (:user/hashed-password u-ent)))))))
