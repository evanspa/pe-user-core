(ns pe-user-core.core
  (:require [datomic.api :refer [q db] :as d]
            [pe-user-core.validation :as val]
            [clojure.tools.logging :as log]
            [clj-time.core :as t]
            [cemerick.friend.credentials :refer [hash-bcrypt bcrypt-verify]]))

(defn entity-for-user-by-id
  [conn user-entid entity-entid user-attr]
  {:pre [(not (nil? user-entid))
         (not (nil? entity-entid))]}
  (let [entity (into {} (d/entity (d/db conn) entity-entid))]
    ; if entity has no entries (count = 0), then effectively the given
    ; entity-entid is not associated with any entities in the database
    (when (and (> (count entity) 0)
               (= user-entid (get-in entity [user-attr :db/id])))
      [entity-entid (dissoc entity user-attr)])))

(defn- user-txnmap
  [user-entid user]
  (merge {:db/id user-entid} user))

(defn save-user-txnmap
  "Returns new user entity map suitable for inclusion in a datomic transaction.
  The map parameter is expected to have the following keys:
  :user/name - the user's first and last name (optional)
  :user/email - the user's email address (optional/required)
  :user/username - a username for the user (optional/required)
  :user/password - a password for the user (optional).
  A username or an email must be supplied (or both).  'conn' is a database
  connection."
  [user-entid user]
  (let [validation-mask (val/save-user-validation-mask user)]
    (if (pos? (bit-and validation-mask val/saveusr-any-issues))
      (throw (IllegalArgumentException. (str validation-mask)))
      (user-txnmap user-entid
                   (-> user
                       (assoc :user/hashed-password
                              (hash-bcrypt (:user/password user)))
                       (dissoc :user/password))))))

(defn save-new-user-txnmap
  [partition user]
  (save-user-txnmap (d/tempid partition) user))

(defn- load-user-for-qry
  "Helper function that searches for a user using the given query and identifer.
   If a user entity id is found, the entity is loaded and returned."
  [conn ident qry]
  {:pre [(not (empty? ident))]}
  (let [db (d/db conn)]
    (when-let [u-entid (ffirst (q qry db ident))]
      [u-entid (d/entity db u-entid)])))

(defn- user-authenticator
  "Helper function that attempts to first load a user given the supplied
   loader function, and next attempts to validate the user's password.  Upon
   a successful authentication, returns the associated user entity; otherwise
   returns nil."
  [user-load-fn password]
  (when-let [[_ u-ent :as result] (user-load-fn)]
    (let [pwd-hash (:user/hashed-password u-ent)]
      (try
        (when (bcrypt-verify password pwd-hash) result)
        (catch Exception exc
          (log/error exc "Error attempting to verify user's password"))))))

(defn load-user-by-email
  "Loads and returns a user entity given the user's email address.  Returns
   nil if no user is found."
  [conn email]
  {:pre [(not (nil? email))]}
  (load-user-for-qry
   conn
   email
   '[:find ?id :in $ ?email :where [?id :user/email ?email]]))

(defn load-user-by-username
  "Loads and returns a user entity given the user's username.  Returns
   nil if no user is found."
  [conn username]
  {:pre [(not (nil? username))]}
  (load-user-for-qry
   conn
   username
   '[:find ?id :in $ ?username :where [?id :user/username ?username]]))

(defn is-auth-valid?
  [auth-ent plaintext-authtoken]
  (and (not (:authtoken/invalidated auth-ent))
       (bcrypt-verify plaintext-authtoken
                      (:authtoken/hashed-token auth-ent))
       (let [expiration-dt (:authtoken/expiration-date auth-ent)]
         (if expiration-dt
           (not (t/after? expiration-dt (t/now)))
           true))))

(defn load-user-by-authtoken
  "Loads and returns a user entity given an authentication token.  Returns
   nil if no associated user is found."
  [conn user-entid plaintext-authtoken]
  {:pre [(not (nil? plaintext-authtoken))]}
  (let [db (d/db conn)
        qry '[:find ?auth
              :in $ ?user-entid
              :where [?auth :authtoken/user ?user-entid]]]
    ; get all of the user's tokens and filter them for validity -
    ; including verifying against plaintext-authtoken.  If 1 is
    ; left, the user is returned.
    (when-let [auth-entids (q qry db user-entid)]
      (let [valid-auth-ids (filter (fn [[auth-entid]]
                                     (let [auth-ent (d/entity db auth-entid)]
                                       (is-auth-valid? auth-ent plaintext-authtoken)))
                                   auth-entids)]
        (when (= (count valid-auth-ids) 1)
          [user-entid (d/entity db user-entid)])))))

(defn get-all-users
  "Given a database connection, returns the set of all users in the system as a
   set of user entities."
  [conn]
  (let [rules '[[(has-ident? ?u)
                 [?u :user/email _]]
                [(has-ident? ?u)
                 [?u :user/username _]]]
        dbase (db conn)]
    (map (fn [[entid]] (d/entity dbase entid))
         (q '[:find ?e :in $ % :where (has-ident? ?e)] dbase rules))))

(def authenticate-user-by-authtoken load-user-by-authtoken)

(defmulti authenticate-user-by-password
  "Authenticates a user given an email address (or username) and password.  Upon
   a successful authentication, returns the associated user entity; otherwise
   returns nil."
  (fn [conn username-or-email password]
    {:pre [(and (not (nil? conn))
                (not (empty? username-or-email))
                (not (empty? password)))]}
    (if (.contains username-or-email "@")
      :email
      :username)))

(defmethod authenticate-user-by-password :email
  [conn email password]
  (user-authenticator #(load-user-by-email conn email) password))

(defmethod authenticate-user-by-password :username
  [conn username password]
  (user-authenticator #(load-user-by-username conn username) password))

(defn create-and-save-auth-token-txnmap [partition u-entid expiration-date]
  (let [uuid (str (java.util.UUID/randomUUID))
        txnmap (merge {:db/id (d/tempid partition)
                       :authtoken/hashed-token (hash-bcrypt uuid)
                       :authtoken/creation-date (.toDate (t/now))
                       :authtoken/user u-entid}
                      (when expiration-date
                        {:authtoken/expiration-date expiration-date}))]
    [uuid txnmap]))
