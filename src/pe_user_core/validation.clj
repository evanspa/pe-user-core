(ns pe-user-core.validation
  (:require [pe-core-utils.core :as ucore]))

(def saveusr-any-issues               (bit-shift-left 1 0))
(def saveusr-name-not-provided        (bit-shift-left 1 1))
(def saveusr-invalid-name             (bit-shift-left 1 2))
(def saveusr-invalid-email            (bit-shift-left 1 3))
(def saveusr-invalid-username         (bit-shift-left 1 4))
(def saveusr-identifier-not-provided  (bit-shift-left 1 5))
(def saveusr-password-not-provided    (bit-shift-left 1 6))
(def saveusr-email-already-registered (bit-shift-left 1 7))

(def ^:private email-regex
  #"[a-zA-Z0-9[!#$%&'()*+,/\-_\.\"]]+@[a-zA-Z0-9[!#$%&'()*+,/\-_\"]]+\.[a-zA-Z0-9[!#$%&'()*+,/\-_\"\.]]+")

(defn is-valid-email? [email]
  (and (not (nil? email))
       (not (nil? (re-find email-regex email)))))

(defn save-user-validation-mask
  [{name :user/name
    email :user/email
    username :user/username
    password :user/password}]
  (-> 0
      (ucore/add-condition #(and (empty? email)
                                 (empty? username))
                           saveusr-identifier-not-provided
                           saveusr-any-issues)
      (ucore/add-condition #(and (not (empty? email))
                                 (not (is-valid-email? email)))
                           saveusr-invalid-email
                           saveusr-any-issues)
      (ucore/add-condition #(and (not (empty? username))
                                 (.contains username "@"))
                           saveusr-invalid-username
                           saveusr-any-issues)))

(defn create-user-validation-mask
  [{name :user/name
    email :user/email
    username :user/username
    password :user/password}]
  (-> 0
      (ucore/add-condition #(empty? name)
                           saveusr-name-not-provided
                           saveusr-any-issues)
      (ucore/add-condition #(and (empty? email)
                                 (empty? username))
                           saveusr-identifier-not-provided
                           saveusr-any-issues)
      (ucore/add-condition #(and (not (empty? email))
                                 (not (is-valid-email? email)))
                           saveusr-invalid-email
                           saveusr-any-issues)
      (ucore/add-condition #(and (not (empty? username))
                                 (.contains username "@"))
                           saveusr-invalid-username
                           saveusr-any-issues)
      (ucore/add-condition #(empty? password)
                           saveusr-password-not-provided
                           saveusr-any-issues)))
