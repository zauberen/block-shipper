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

(ns com.blockshipper.players
  "Player login sessions (the player_sessions table), used for logins and playtime."
  (:require [com.blockshipper.database.db :as db]
            [com.blockshipper.util :as u]))

(defn open-session!
  "Record PLAYER joining SERVER-ID. Any session left open for the player is closed first."
  ([server-id player] (open-session! server-id player nil (u/now-ms)))
  ([server-id player uuid ts]
   (db/exe-db-stmt ["update player_sessions set left_at = ? where server_id = ? and player_name = ? and left_at is null"
                    ts server-id player])
   (db/exe-db-stmt ["insert into player_sessions (server_id, player_name, uuid, joined_at) values (?, ?, ?, ?)"
                    server-id player uuid ts])))

(defn close-session!
  "Record PLAYER leaving SERVER-ID."
  ([server-id player] (close-session! server-id player (u/now-ms)))
  ([server-id player ts]
   (db/exe-db-stmt ["update player_sessions set left_at = ? where server_id = ? and player_name = ? and left_at is null"
                    ts server-id player])))

(defn close-all-sessions!
  "Close every open session for SERVER-ID (the server stopped), or for every server when nil."
  ([server-id] (close-all-sessions! server-id (u/now-ms)))
  ([server-id ts]
   (if server-id
     (db/exe-db-stmt ["update player_sessions set left_at = ? where server_id = ? and left_at is null" ts server-id])
     (db/exe-db-stmt ["update player_sessions set left_at = ? where left_at is null" ts]))))

(defn set-uuid!
  "Remember PLAYER's UUID on their sessions for SERVER-ID."
  [server-id player uuid]
  (db/exe-db-stmt ["update player_sessions set uuid = ? where server_id = ? and player_name = ? and uuid is null"
                   uuid server-id player]))

(defn summary
  "One row per player who has joined SERVER-ID:
  :player_name :uuid :last_login :playtime (ms) :online (boolean)."
  ([server-id] (summary server-id (u/now-ms)))
  ([server-id now]
   (->> (db/run-db-query
         [(str "select player_name, max(uuid) as uuid, max(joined_at) as last_login, "
               "sum(coalesce(left_at, ?) - joined_at) as playtime, "
               "sum(case when left_at is null then 1 else 0 end) as open_sessions "
               "from player_sessions where server_id = ? group by player_name "
               "order by last_login desc")
          now server-id])
        (mapv (fn [row]
                (-> row
                    (assoc :online (pos? (or (:open_sessions row) 0)))
                    (dissoc :open_sessions)))))))

(defn recent-logins
  "The most recent LIMIT logins for SERVER-ID."
  [server-id limit]
  (db/run-db-query ["select player_name, joined_at, left_at from player_sessions where server_id = ? order by joined_at desc limit ?"
                    server-id limit]))
