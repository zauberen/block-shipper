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

(ns com.blockshipper.servers
  "Minecraft server records (the mc_servers table)."
  (:require [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging]
            [clojure.string :as str]
            [com.blockshipper.database.db :as db]
            [com.blockshipper.settings :as s]
            [com.blockshipper.util :as u]
            [com.blockshipper.wrappers.path-utils :as pu]))

(def host-modes ["published" "always-active" "disabled"])
(def list-modes ["blacklist" "whitelist"])
(def server-types ["vanilla"])

(def first-internal-port 25600)
(def first-exposed-port 25565)

(def slug-re #"^[a-z0-9][a-z0-9_-]{0,31}$")

(defn valid-slug?
  "Slugs are used as directory names, so they are restricted to a safe charset."
  [slug]
  (boolean (and (string? slug) (re-matches slug-re slug))))

(defn slugify
  "Turn a display name into a slug suitable for a directory name."
  [display-name]
  (let [slug (-> (or display-name "")
                 str/lower-case
                 (str/replace #"[^a-z0-9_-]+" "-")
                 (str/replace #"^[-_]+|[-_]+$" ""))]
    (subs slug 0 (min 32 (count slug)))))

(defn display-name
  "The name shown in the UI for SERVER."
  [server]
  (or (not-empty (:display_name server)) (:name server)))

(defn server-dir
  "The directory SERVER lives in."
  [server]
  (io/file (pu/expand-home-path (:path server))))

(defn all-servers
  []
  (db/run-db-query ["select * from mc_servers order by lower(coalesce(display_name, name))"]))

(defn by-id
  [id]
  (first (db/run-db-query ["select * from mc_servers where id = ?" id])))

(defn by-slug
  [slug]
  (first (db/run-db-query ["select * from mc_servers where name = ?" slug])))

(defn visible-servers
  "Servers USER may see: all of them for admins, assigned ones for moderators."
  [user]
  (cond
    (nil? user) []
    (= "admin" (:role user)) (all-servers)
    :else (db/run-db-query
           [(str "select s.* from mc_servers s join user_servers us on us.server_id = s.id "
                 "where us.user_id = ? order by lower(coalesce(s.display_name, s.name))")
            (:id user)])))

(defn public-servers
  "Servers listed on the public dashboard."
  []
  (filter #(#{"published" "always-active"} (:host_mode %)) (all-servers)))

(defn used-ports
  []
  (->> (db/run-db-query ["select mc_port, exposed_port from mc_servers"])
       (mapcat (juxt :mc_port :exposed_port))
       (remove nil?)
       set))

(defn next-free-port
  "First port >= START that is not in USED."
  [start used]
  (first (remove used (iterate inc start))))

(defn validate
  "Returns a seq of error strings for the server attributes M (empty when valid)."
  [{:keys [name display_name host_mode list_mode server_type exposed_port]} existing-id]
  (let [clash (when name (by-slug name))
        port-owner (when exposed_port
                     (first (db/run-db-query ["select id from mc_servers where exposed_port = ? or mc_port = ?"
                                              exposed_port exposed_port])))]
    (cond-> []
      (and name (not (valid-slug? name)))
      (conj "The id must be lowercase letters, numbers, dashes or underscores (max 32).")
      (and clash (not= (:id clash) existing-id))
      (conj "A server with that id already exists.")
      (and display_name (str/blank? display_name))
      (conj "A display name is required.")
      (and host_mode (not (some #{host_mode} host-modes)))
      (conj "Unknown host mode.")
      (and list_mode (not (some #{list_mode} list-modes)))
      (conj "Unknown list mode.")
      (and server_type (not (some #{server_type} server-types)))
      (conj "Unknown server type.")
      (and exposed_port (not (< 0 exposed_port 65536)))
      (conj "The port must be between 1 and 65535.")
      (and port-owner (not= (:id port-owner) existing-id))
      (conj "That port is already used by another server."))))

(defn create!
  "Insert a new server record built from ATTRS and return it.
  Ports are allocated automatically when not given."
  [attrs]
  (let [settings (s/get-settings)
        used (used-ports)
        exposed (or (:exposed_port attrs) (next-free-port first-exposed-port used))
        internal (or (:mc_port attrs) (next-free-port first-internal-port (conj used exposed)))
        slug (:name attrs)
        record (merge {:server_type "vanilla"
                       :host_mode "published"
                       :list_mode "blacklist"
                       :auto_update 1
                       :mem_min (:mc-mem-min settings)
                       :mem_max (:mc-mem-max settings)}
                      attrs
                      {:path (str (:hosting-root settings) "/" slug)
                       :exposed_port exposed
                       :mc_port internal})]
    (jdbc/insert! (db/get-db-spec) :mc_servers record)
    (by-slug slug)))

(def editable-columns
  #{:display_name :host_mode :list_mode :auto_update :pending_update :mem_min :mem_max
    :exposed_port :mc_version :backup_on_update :backup_weekly :backup_weekly_day
    :backup_monthly :backup_monthly_day :backup_on_offline :last_backup_at
    :last_scheduled_backup_at})

(defn update!
  "Update the editable columns in ATTRS for server ID."
  [id attrs]
  (let [attrs (select-keys attrs editable-columns)]
    (when (seq attrs)
      (jdbc/update! (db/get-db-spec) :mc_servers
                    (assoc attrs :modified_at (java.sql.Timestamp. (u/now-ms)))
                    ["id = ?" id]))
    (by-id id)))

(defn delete!
  "Remove server ID and every row that references it. Files are left on disk."
  [id]
  (jdbc/with-db-transaction [tx (db/get-db-spec)]
    (doseq [table ["user_servers" "player_sessions" "uptime_log" "player_lists" "server_samples" "rejected_logins"]]
      (jdbc/execute! tx [(str "delete from " table " where server_id = ?") id]))
    (jdbc/execute! tx ["delete from mc_servers where id = ?" id])))

;; key -> (fn [server-id]) called after a server record is created, changed or deleted.
(defonce change-hooks (atom {}))

(defn add-change-hook!
  [k f]
  (swap! change-hooks assoc k f))

(defn notify-changed!
  [server-id]
  (doseq [[_ f] @change-hooks]
    (try (f server-id)
         (catch Exception e
           (clojure.tools.logging/error e "Server change hook failed")))))
