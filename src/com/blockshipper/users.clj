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

(ns com.blockshipper.users
  "Block Shipper accounts and their server assignments."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [com.blockshipper.api.session :as session]
            [com.blockshipper.database.db :as db]))

(def roles ["admin" "moderator"])

(defn- with-assignments
  [user]
  (when user
    (assoc user :server-ids
           (set (map :server_id (db/run-db-query ["select server_id from user_servers where user_id = ?"
                                                  (:id user)]))))))

(defn by-username
  "The enabled user named USERNAME with its :server-ids, or nil."
  [username]
  (with-assignments
    (first (db/run-db-query ["select id, username, role, enabled from users where username = ? and enabled = 1"
                             username]))))

(defn by-id
  [id]
  (with-assignments
    (first (db/run-db-query ["select id, username, role, enabled from users where id = ?" id]))))

(defn all-users
  []
  (mapv with-assignments
        (db/run-db-query ["select id, username, role, enabled from users order by lower(username)"])))

(defn any-users?
  "True once at least one account exists."
  []
  (boolean (seq (db/run-db-query ["select id from users limit 1"]))))

(defn admin-count
  []
  (count (db/run-db-query ["select id from users where role = 'admin' and enabled = 1"])))

(defn validate-new
  "Errors for creating USERNAME with PASSWORD and ROLE."
  [username password role]
  (cond-> []
    (or (str/blank? username) (not (re-matches #"[A-Za-z0-9_.-]{1,32}" username)))
    (conj "Usernames are 1-32 letters, numbers, dots, dashes or underscores.")
    (and (not (str/blank? username))
         (seq (db/run-db-query ["select id from users where username = ?" username])))
    (conj "That username is taken.")
    (< (count (or password "")) 8)
    (conj "Passwords must be at least 8 characters.")
    (not (some #{role} roles))
    (conj "Unknown role.")))

(defn create!
  [username password role]
  (db/exe-db-stmt ["insert into users (username, password, role) values (?, ?, ?)"
                   username (session/hash-password password) role])
  (by-username username))

(defn set-password!
  [user-id password]
  (db/exe-db-stmt ["update users set password = ?, modified_at = current_timestamp where id = ?"
                   (session/hash-password password) user-id]))

(defn check-password
  "True when PASSWORD is USERNAME's current password."
  [username password]
  (when-let [row (first (db/run-db-query ["select password from users where username = ?" username]))]
    (boolean (session/verify-password password (:password row)))))

(defn set-role!
  [user-id role]
  (db/exe-db-stmt ["update users set role = ?, modified_at = current_timestamp where id = ?" role user-id]))

(defn set-assignments!
  "Replace the servers moderator USER-ID is assigned to."
  [user-id server-ids]
  (jdbc/with-db-transaction [tx (db/get-db-spec)]
    (jdbc/execute! tx ["delete from user_servers where user_id = ?" user-id])
    (doseq [sid (distinct server-ids)]
      (jdbc/execute! tx ["insert into user_servers (user_id, server_id) values (?, ?)" user-id sid]))))

(defn delete!
  [user-id]
  (jdbc/with-db-transaction [tx (db/get-db-spec)]
    (jdbc/execute! tx ["delete from user_servers where user_id = ?" user-id])
    (jdbc/execute! tx ["delete from users where id = ?" user-id])))

(defn last-admin?
  "True when USER is the only enabled admin left."
  [user]
  (and (= "admin" (:role user)) (<= (admin-count) 1)))
