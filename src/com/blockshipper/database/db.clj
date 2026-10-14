;; Block Shipper - run and manage Minecraft servers from a web dashboard.
;; Copyright (C) 2025-2026 zauberen
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU Affero General Public License as published
;; by the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.

(ns com.blockshipper.database.db
  (:require [clojure.edn :as edn]
            [clojure.java.jdbc :as jdbc]
            [com.blockshipper.database.sqlite :as sqlite]
            [com.blockshipper.database.valkey :as valkey]
            [com.blockshipper.settings :as s]))

(defn get-db-spec
  "Get the db-spec so you can connect and run queries against the database."
  []
  (case (:db (s/get-settings))
    sqlite (do (sqlite/initialize) (sqlite/db-spec))))

(defn run-db-query
  "Query the database given STMT query string or vec of prepared statment and args."
  [stmt]
  (jdbc/query (get-db-spec) stmt))

(defn exe-db-stmt
  "Execute a statement on the database given STMT query string or vec of prepared statment and args."
  [stmt]
  (jdbc/execute! (get-db-spec) stmt))

(def session-ttl-seconds
  "How long a login lasts."
  (* 30 24 60 60))

(def ^:private session-expiry
  (str "datetime('now', '-" session-ttl-seconds " seconds')"))

(defn db-session-store
  "Create a session but use the database instead of a cache server. Expired sessions
  are removed at the same time."
  [sid session-obj]
  (jdbc/execute! (get-db-spec) [(str "delete from sessions where time_created <= " session-expiry)])
  (jdbc/execute! (get-db-spec) ["insert into sessions (sid, session_obj) values (?, ?)"
                                sid
                                (pr-str session-obj)]))

(defn db-session-get
  "Get the unexpired session associated with SID from the database."
  [sid]
  (run-db-query [(str "select session_obj from sessions where sid = ? and time_created > " session-expiry)
                 sid]))

(defn db-session-exists?
  "Check if a session sid exists in the database."
  [sid]
  (> (count (db-session-get sid)) 0))

(defn get-session
  "Get the session given the session SID from the nosql db."
  [sid]
  (case (:nosql-type (s/get-settings))
    (valkey redis) (when-let [session-obj (valkey/get-record sid)]
                        (edn/read-string session-obj))
    yes-sql (when-let [session-obj (:session_obj (first (db-session-get sid)))]
              (edn/read-string session-obj))))

(defn new-session
  "Create a session with a given SID and SESSION-OBJ."
  [sid session-obj]
  (case (:nosql-type (s/get-settings))
    (valkey redis) (valkey/insert-record-with-ttl sid (pr-str session-obj) session-ttl-seconds)
    yes-sql (db-session-store sid session-obj)))

(defn delete-session
  "Delete the session associated with SID."
  [sid]
  (case (:nosql-type (s/get-settings))
    (valkey redis) (valkey/delete-record sid)
    yes-sql (jdbc/execute! (get-db-spec) ["delete from sessions where sid = ?" sid])))

(defn valid-session?
  "Check if SID is a valid session ID."
  [sid]
  (case (:nosql-type (s/get-settings))
    (valkey redis) (valkey/key-exists? sid)
    yes-sql (db-session-exists? sid)))
