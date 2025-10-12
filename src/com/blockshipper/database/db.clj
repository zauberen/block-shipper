(ns com.blockshipper.database.db
  (:require [clojure.java.jdbc :as jdbc]
            [com.blockshipper.database.sqlite :as sqlite]
            [com.blockshipper.database.valkey :as valkey]
            [com.blockshipper.settings :as s]))

(defn get-db-spec
  "Get the db-spec so you can connect and run queries against the database."
  []
  (case (:db (s/get-settings))
    sqlite (do (sqlite/initialize) sqlite/db-spec)))

(defn run-db-query
  "Query the database given STMT query string or vec of prepared statment and args."
  [stmt]
  (jdbc/query (get-db-spec) stmt))


(defn db-session-store
  "Create a session but use the database instead of a cache server."
  [sid session-obj]
  (run-db-query ["insert into sessions (sid, session_obj) values (?, ?)"
                 sid
                 (pr-str session-obj)]))

(defn db-session-get
  "Get the session associated with SID from the database."
  [sid]
  (run-db-query ["select session_obj from sessions where sid = ?"
                 sid]))

(defn db-session-exists?
  "Check if a session sid exists in the database."
  [sid]
  (>= (count (db-session-get sid)) 0))

(defn get-session
  "Get the session given the session SID from the nosql db."
  [sid]
  (case (:nosql-type (s/get-settings))
    (or valkey redis) (when-let [session-obj (valkey/get-record sid)]
                        (read-string session-obj))
    yes-sql (when-let [session-obj (:session-obj (nth (db-session-get sid) 0))]
                               (read-string session-obj))))

(defn new-session
  "Create a session with a given SID and SESSION-OBJ."
  [sid session-obj]
  (case (:nosql-type (s/get-settings))
    (or valkey redis) (valkey/insert-record sid (pr-str session-obj))
    yes-sql (db-session-store sid session-obj)))

(defn valid-session?
  "Check if SID is a valid session ID."
  [sid]
  (case (:nosql-type (s/get-settings))
    (or valkey redis) (valkey/key-exists? sid)
    yes-sql (db-session-exists? sid)))
