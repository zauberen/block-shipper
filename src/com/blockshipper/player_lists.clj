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

(ns com.blockshipper.player-lists
  "Whitelist and ban list entries. Entries with a nil server id belong to the global
  lists, which every server inherits."
  (:require [clojure.string :as str]
            [com.blockshipper.database.db :as db]
            [com.blockshipper.util :as u]))

(def list-types ["whitelist" "ban"])

(def player-name-re #"^[A-Za-z0-9_]{1,16}$")

(defn valid-player-name?
  [n]
  (boolean (and (string? n) (re-matches player-name-re n))))

(defn entries
  "Entries of LIST-TYPE for SERVER-ID (nil for the global list)."
  [server-id list-type]
  (if server-id
    (db/run-db-query ["select * from player_lists where server_id = ? and list_type = ? order by lower(player_name)"
                      server-id list-type])
    (db/run-db-query ["select * from player_lists where server_id is null and list_type = ? order by lower(player_name)"
                      list-type])))

(defn merge-entries
  "Combine GLOBAL and LOCAL entries, keeping one entry per player name (local wins).
  Each entry gets :inherited true when it only exists globally."
  [global local]
  (let [local-names (set (map (comp str/lower-case :player_name) local))]
    (->> (concat (map #(assoc % :inherited false) local)
                 (->> global
                      (remove #(local-names (str/lower-case (:player_name %))))
                      (map #(assoc % :inherited true))))
         (sort-by (comp str/lower-case :player_name))
         vec)))

(defn effective
  "The entries of LIST-TYPE that apply to SERVER-ID: global plus server specific."
  [server-id list-type]
  (merge-entries (entries nil list-type) (entries server-id list-type)))

(defn find-entry
  [server-id list-type player]
  (first (filter #(= (str/lower-case player) (str/lower-case (:player_name %)))
                 (entries server-id list-type))))

(defn add!
  "Add PLAYER to LIST-TYPE for SERVER-ID (nil for global). Returns false if already present."
  [server-id list-type player {:keys [uuid reason created-by]}]
  (if (find-entry server-id list-type player)
    false
    (do (db/exe-db-stmt ["insert into player_lists (server_id, list_type, player_name, uuid, reason, created_by, created_at) values (?, ?, ?, ?, ?, ?, ?)"
                         server-id list-type player uuid reason created-by (u/now-ms)])
        true)))

(defn remove!
  [server-id list-type player]
  (if server-id
    (db/exe-db-stmt ["delete from player_lists where server_id = ? and list_type = ? and lower(player_name) = lower(?)"
                     server-id list-type player])
    (db/exe-db-stmt ["delete from player_lists where server_id is null and list_type = ? and lower(player_name) = lower(?)"
                     list-type player])))

(defn set-uuid!
  "Fill in PLAYER's uuid on entries that don't have one yet."
  [player uuid]
  (db/exe-db-stmt ["update player_lists set uuid = ? where lower(player_name) = lower(?) and uuid is null"
                   uuid player]))
