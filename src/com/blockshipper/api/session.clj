(ns com.blockshipper.api.session
  (:require [clojure.java.jdbc :as jdbc]
            [buddy.hashers :as hashers]
            [com.blockshipper.pages.login :as lp]
            [com.blockshipper.database.db :as db])
  (:import java.util.Base64))

;;; Response builders
(defn encode
  "Wrapper for Base64 encoder."
  [to-encode]
  (String. (.encode (Base64/getEncoder) (.getBytes to-encode))))

(defn decode
  "Wrapper for Base64 decoder."
  [to-decode]
  (String. (.decode (Base64/getDecoder) (.getBytes to-decode))))

(defn verify-password
  "Verify a user's password matches the hash from the database."
  [plain-password hashed-password]
  (hashers/check plain-password (decode hashed-password)))

(defn hash-password
  "Hash a pasword to be stored in the database."
  [plain-password]
  (encode (hashers/derive plain-password {:alg :argon2id})))

(defn login-success
  "Returns the login success response."
  [session]
  {:status 200
   :body (:sid session)
   :headers {"Content-Type" "text/plain"
             "Set-Cookie" (str "bs-session-id=" (:sid session) "; Path=/; HttpOnly")
             "HX-Redirect" "/"}})

(defn login-fail
  "Returns the login fail response."
  []
  {:status 401
   :body "Invalid username or password."
   :headers {"Content-Type" "text/plain"}})


(defn initialize-session
  "Create a new session dict and add it to the cache db, returns the session obj."
  [user role]
  (let* [sid (str (java.util.UUID/randomUUID))
         session {:sid sid
                  :user user
                  :role role}]
        (db/new-session sid session)
        session))

(defn try-login
  "Try to log into the application."
  [req]
  (when-let [form-params (:form-params req)]
    (let [user (get form-params "username")
          pass (get form-params "password")
          rs (jdbc/query (db/get-db-spec)
                         ["select password, role from users where username = ?"
                          user])]
      (if (and (> (count rs) 0)
               (verify-password pass (:password (nth rs 0))))
        (login-success (initialize-session user (:role (nth rs 0))))
        (login-fail)))))

;;; Wrappers and functions for external use
(defn get-sid-from-req
  "Returns the session id from the request."
  [req]
  (when (and (not (nil? (:cookies req)))
             (not (nil? (get (:cookies req) "bs-session-id"))))
    (:value (get (:cookies req) "bs-session-id"))))

(defn has-session?
  "Returns true if the request has a session and the session is valid."
  [req]
  (let [sid (get-sid-from-req req)]
    (db/valid-session? sid)))

(defn get-session
  "Get the session data using the given request."
  [req]
  (when (has-session? req)
    (db/get-session (get-sid-from-req req))))

(defn requires-session
  "Wrapper to check for a valid session before accessing a page."
  [page-fn req]
  (if (has-session? req)
    (apply page-fn [req])
    (lp/login-page)))
