(ns com.blockshipper.database.db
  (:require [com.blockshipper.database.sqlite :as sqlite]
            [com.blockshipper.database.valkey :as valkey]
            [com.blockshipper.settings :as s]))

(defn get-db-spec
  "Get the db-spec so you can connect and run queries against the database."
  []
  (case (:db (s/get-settings))
    sqlite (do (sqlite/initialize) sqlite/db-spec)))

(defn get-session
  "Get the session given the session SID from the nosql db."
  [sid]
  (case (:nosql-type (s/get-settings))
    (or valkey redis) (when-let [session-obj (valkey/get-record sid)]
                        (read-string session-obj))))

(defn new-session
  "Create a session with a given SID and SESSION-OBJ."
  [sid session-obj]
  (case (:nosql-type (s/get-settings))
    (or valkey redis) (valkey/insert-record sid (pr-str session-obj))))

(defn valid-session?
  "Check if SID is a valid session ID."
  [sid]
  (case (:nosql-type (s/get-settings))
    (or valkey redis) (valkey/key-exists? sid)))
