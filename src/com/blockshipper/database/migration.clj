(ns com.blockshipper.database.migration
  (:require [clojure.java.jdbc :as jdbc]))

(def server-version "0.0.2")
(def migration-chk-ran (atom nil)) ; Every time this class is reloaded this is reset, whenever check-run-migrations is ran it is set to true.
(def default-account-sql ; Insert for the admin account.
  (str "insert into users (username, password, role) values ('admin', "
       "'YXJnb24yaWQkYmUxNDM1MDVmZDk5MThmNzQwOWE4ZGE3ODM2NzA2ZmUkNjU1MzYkMiQxJDA1Mjg5NzYxZjIwN2I5YzIwYTI2OGMxZDU4MDcyODQxMTY3NmM4NmI3YmJjMzg1OTg1YzMxYjVmOGQwMzIwMTQ='"
       ", 'admin')"))

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
        ; Roles are: admin (user + add/delete/restore servers and users), user (view status, edit settings, start/stop/restart servers)
        "role text not null default 'user',"
        "enabled integer not null default 1"
        "created_at datetime default current_timestamp,"
        "modified_at datetime default current_timestamp"
        ")")
   ; Add a default user, username of admin, password of admin, with admin role.
   (str "create table if not exists mc_servers ("
        "id integer primary key autoincrement," "user_id integer not null,"
        "name text unique not null,"
        "path text unique not null,"
        "created_at datetime default current_timestamp,"
        "modified_at datetime default current_timestamp,"
        "foreign key (user_id) references users (id))")
   (str "create table if not exists server_stats ("
        "id integer primary key autoincrement,"
        "server_name text,"
        "active_users integer not null,"
        "time_recorded datetime default current_timestamp"
        "foreign key (user_id) references users (id))")])

(defn has-admin?
  "Determine if the admin user exists."
  [db-spec]
  (>= (count (jdbc/query db-spec "select id from users where username = 'admin'")) 0))

(defn run-migrations
  "Initialize a new database."
  [db-spec]
  (doseq [migration migrations]
    (jdbc/execute! db-spec [migration]))
  (when (not (has-admin? db-spec))
    (jdbc/execute! db-spec default-account-sql)))

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

