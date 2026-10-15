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

(ns com.blockshipper.database.migration
  (:require [clojure.java.jdbc :as jdbc]))

(def server-version "0.1.3")
(def migration-chk-ran (atom nil)) ; Every time this class is reloaded this is reset, whenever check-run-migrations is ran it is set to true.

(def migrations
  [(str "create table if not exists db_version ("
        "id integer primary key autoincrement,"
        "version_number text"
        ")")
   (str "insert into db_version (version_number) values ('" server-version "')")
   (str "create table if not exists users ("
        "id integer primary key autoincrement,"
        "username text unique not null,"
        "password text not null,"
        ; Roles are: admin (create servers, manage users, moderate any server), moderator (moderate assigned servers)
        "role text not null default 'moderator',"
        "enabled integer not null default 1,"
        "created_at datetime default current_timestamp,"
        "modified_at datetime default current_timestamp"
        ")")
   (str "create table if not exists mc_servers ("
        "id integer primary key autoincrement," "user_id integer not null,"
        "name text unique not null,"
        "path text unique not null,"
        "mem_min text,"
        "mem_max text,"
        "exposed_port integer,"
        "created_at datetime default current_timestamp,"
        "modified_at datetime default current_timestamp,"
        "foreign key (user_id) references users (id))")
   (str "create table if not exists server_stats ("
        "id integer primary key autoincrement,"
        "server_name text,"
        "active_users integer not null,"
        "time_recorded datetime default current_timestamp)")
   (str "create table if not exists sessions ("
        "sid text primary key,"
        "session_obj text not null,"
        "time_created datetime default current_timestamp)")
   (str "alter table mc_servers add column mem_min text")
   (str "alter table mc_servers add column mem_max text")
   (str "alter table mc_servers add column exposed_port integer")
   ;; 0.1.0: server management
   "alter table mc_servers add column display_name text"
   "alter table mc_servers add column server_type text not null default 'vanilla'"
   "alter table mc_servers add column mc_version text"
   "alter table mc_servers add column mc_port integer"
   "alter table mc_servers add column host_mode text not null default 'published'"
   "alter table mc_servers add column auto_update integer not null default 1"
   "alter table mc_servers add column pending_update integer not null default 0"
   "alter table mc_servers add column list_mode text not null default 'blacklist'"
   "alter table mc_servers add column backup_on_update integer not null default 1"
   "alter table mc_servers add column backup_weekly integer not null default 0"
   "alter table mc_servers add column backup_weekly_day integer not null default 7"
   "alter table mc_servers add column backup_monthly integer not null default 0"
   "alter table mc_servers add column backup_monthly_day integer not null default 1"
   "alter table mc_servers add column backup_on_offline integer not null default 0"
   "alter table mc_servers add column last_backup_at integer"
   "update users set role = 'moderator' where role = 'user'"
   (str "create table if not exists user_servers ("
        "user_id integer not null references users (id) on delete cascade,"
        "server_id integer not null references mc_servers (id) on delete cascade,"
        "primary key (user_id, server_id))")
   ;; Times in the tables below are epoch milliseconds.
   (str "create table if not exists player_sessions ("
        "id integer primary key autoincrement,"
        "server_id integer not null,"
        "player_name text not null,"
        "uuid text,"
        "joined_at integer not null,"
        "left_at integer)")
   "create index if not exists player_sessions_server on player_sessions (server_id, player_name)"
   (str "create table if not exists uptime_log ("
        "id integer primary key autoincrement,"
        "server_id integer not null,"
        "started_at integer not null,"
        "stopped_at integer,"
        "started_by text,"
        "stop_reason text)")
   ;; server_id null means the entry is part of the global list.
   (str "create table if not exists player_lists ("
        "id integer primary key autoincrement,"
        "server_id integer,"
        "list_type text not null,"
        "player_name text not null,"
        "uuid text,"
        "reason text,"
        "created_by text,"
        "created_at integer not null)")
   (str "create table if not exists global_settings ("
        "key text primary key,"
        "value text)")
   (str "create table if not exists server_samples ("
        "id integer primary key autoincrement,"
        "server_id integer not null,"
        "players integer not null,"
        "recorded_at integer not null)")
   "create index if not exists server_samples_server on server_samples (server_id, recorded_at)"
   ;; 0.1.1: scheduled backups are tracked separately from other backups
   "alter table mc_servers add column last_scheduled_backup_at integer"
   ;; 0.1.2: the auto start method is now called kick (next to hold)
   "update global_settings set value = 'kick' where key = 'start_method' and value = 'auto'"
   ;; 0.1.3: players imported from world files, and logins refused by the whitelist.
   ;; An imported session covers the playtime the world recorded, ending when the
   ;; player was last seen.
   "alter table player_sessions add column imported integer not null default 0"
   (str "create table if not exists rejected_logins ("
        "id integer primary key autoincrement,"
        "server_id integer not null,"
        "player_name text not null,"
        "uuid text,"
        "attempts integer not null default 1,"
        "last_attempt_at integer not null)")
   "create index if not exists rejected_logins_server on rejected_logins (server_id, player_name)"])

(defn run-migrations
  "Initialize a new database. No user is created: the first admin comes from onboarding."
  [db-spec]
  (doseq [migration migrations]
    (try
      (jdbc/execute! db-spec [migration])
      (catch Exception e
        ;; Ignore duplicate column errors from alter table statements
        (when-not (re-find #"(?i)duplicate column" (.getMessage e))
          (throw e))))))

(defn get-server-version-query
  "Returns a query to check the server version."
  []
  "select version_number from db_version where id = (select max(id) from db_version)")

(defn check-run-migrations
  "Check if migrations should be ran for the given DB-SPEC and then call run-migrations if they should."
  [db-spec]
  (if-some [_ @migration-chk-ran]
    true ; Do not check migrations if this file has not been reloaded.
    (let [ver-rs (jdbc/query db-spec (get-server-version-query))]
      (reset! migration-chk-ran true)
      (when (or (<= (count ver-rs) 0)
                (not= server-version (:version_number (nth ver-rs 0))))
        (run-migrations db-spec)))))

