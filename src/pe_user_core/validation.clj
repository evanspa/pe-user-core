(ns pe-user-core.validation
  (:require [pe-core-utils.core :as ucore]))

(def su-any-issues                      (bit-shift-left 1 0))
(def su-invalid-email                   (bit-shift-left 1 1))
(def su-username-and-email-not-provided (bit-shift-left 1 2))
(def su-password-not-provided           (bit-shift-left 1 3))
(def su-email-already-registered        (bit-shift-left 1 4))
(def su-username-already-registered     (bit-shift-left 1 5))

(def pwd-reset-any-issues   (bit-shift-left 1 0))
(def pwd-reset-unknown-email (bit-shift-left 1 1))

(def ^:private email-regex
  #"[a-zA-Z0-9[!#$%&'()*+,/\-_\.\"]]+@[a-zA-Z0-9[!#$%&'()*+,/\-_\"]]+\.[a-zA-Z0-9[!#$%&'()*+,/\-_\"\.]]+")

(defn is-valid-email? [email]
  (and (not (nil? email))
       (not (nil? (re-find email-regex email)))))

(defn save-new-user-validation-mask
  [{email :user/email
    username :user/username
    password :user/password}]
  (-> 0
      (ucore/add-condition #(and (empty? email)
                                 (empty? username))
                           su-username-and-email-not-provided
                           su-any-issues)
      (ucore/add-condition #(and (not (empty? email))
                                 (not (is-valid-email? email)))
                           su-invalid-email
                           su-any-issues)
      (ucore/add-condition #(empty? password)
                           su-password-not-provided
                           su-any-issues)))

(defn save-user-validation-mask
  [{email :user/email
    username :user/username
    password :user/password
    :as user}]
  (-> 0
      (ucore/add-condition #(and (contains? user :user/email)
                                 (contains? user :user/username)
                                 (empty? email)
                                 (empty? username))
                           su-username-and-email-not-provided
                           su-any-issues)
      (ucore/add-condition #(and (contains? user :user/email)
                                 (not (empty? email))
                                 (not (is-valid-email? email)))
                           su-invalid-email
                           su-any-issues)
      (ucore/add-condition #(and (contains? user :user/password)
                                 (empty? password))
                           su-password-not-provided
                           su-any-issues)))
