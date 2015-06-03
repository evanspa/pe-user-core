(ns pe-user-core.validation
  (:require [pe-core-utils.core :as ucore]))

(def snu-any-issues                      (bit-shift-left 1 0))
(def snu-invalid-email                   (bit-shift-left 1 1))
(def snu-username-and-email-not-provided (bit-shift-left 1 2))
(def snu-password-not-provided           (bit-shift-left 1 3))
(def snu-email-already-registered        (bit-shift-left 1 4))
(def snu-username-already-registered     (bit-shift-left 1 5))

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
                           snu-username-and-email-not-provided
                           snu-any-issues)
      (ucore/add-condition #(and (not (empty? email))
                                 (not (is-valid-email? email)))
                           snu-invalid-email
                           snu-any-issues)
      (ucore/add-condition #(empty? password)
                           snu-password-not-provided
                           snu-any-issues)))
