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
  "Player login sessions (the player_sessions table), used for logins and playtime,
  and logins the whitelist refused (rejected_logins)."
  (:require [clojure.string :as str]
            [com.blockshipper.database.db :as db]
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

(defn import-session!
  "Record playtime PLAYER had on SERVER-ID before Block Shipper saw them (read from the
  world): a closed session of PLAYTIME ms ending at LAST-SEEN."
  [server-id player uuid last-seen playtime]
  (db/exe-db-stmt ["insert into player_sessions (server_id, player_name, uuid, joined_at, left_at, imported) values (?, ?, ?, ?, ?, 1)"
                   server-id player uuid (- last-seen playtime) last-seen]))

;; Imported sessions only say when the player was last seen, which is when they end.
(def ^:private last-login-sql "max(case when imported = 1 then left_at else joined_at end)")

(defn summary
  "One row per player who has joined SERVER-ID:
  :player_name :uuid :last_login :first_joined :playtime (ms) :online (boolean)."
  ([server-id] (summary server-id (u/now-ms)))
  ([server-id now]
   (->> (db/run-db-query
         [(str "select player_name, max(uuid) as uuid, " last-login-sql " as last_login, "
               "min(joined_at) as first_joined, "
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
  (db/run-db-query ["select player_name, joined_at, left_at from player_sessions where server_id = ? and imported = 0 order by joined_at desc limit ?"
                    server-id limit]))

(defn recent-players
  "The LIMIT players who most recently joined any of SERVER-IDS, newest first:
  :player_name :last_login :servers (distinct servers joined since SINCE) :online (boolean)."
  [server-ids since limit]
  (if (empty? server-ids)
    []
    (->> (db/run-db-query
          (-> [(str "select player_name, " last-login-sql " as last_login, "
                    "count(distinct case when joined_at >= ? then server_id end) as servers, "
                    "sum(case when left_at is null then 1 else 0 end) as open_sessions "
                    "from player_sessions where server_id in ("
                    (str/join "," (repeat (count server-ids) "?"))
                    ") group by player_name order by last_login desc limit ?")
               since]
              (into server-ids)
              (conj limit)))
         (mapv (fn [row]
                 (-> row
                     (assoc :online (pos? (or (:open_sessions row) 0)))
                     (dissoc :open_sessions)))))))

;;; Logins refused by the whitelist

(def ^:private repeat-window-ms
  "A refused login is logged more than once (by the proxy and the server, or as
  \"Disconnecting\" and \"lost connection\"); attempts this close count once."
  10000)

(defn record-rejected-login!
  "Remember that PLAYER tried to join SERVER-ID and was turned away by the whitelist."
  ([server-id player uuid] (record-rejected-login! server-id player uuid (u/now-ms)))
  ([server-id player uuid ts]
   (if-let [row (first (db/run-db-query ["select * from rejected_logins where server_id = ? and lower(player_name) = lower(?)"
                                         server-id player]))]
     (db/exe-db-stmt ["update rejected_logins set player_name = ?, uuid = coalesce(?, uuid), attempts = ?, last_attempt_at = ? where id = ?"
                      player uuid
                      (cond-> (:attempts row) (> ts (+ (:last_attempt_at row) repeat-window-ms)) inc)
                      ts (:id row)])
     (db/exe-db-stmt ["insert into rejected_logins (server_id, player_name, uuid, attempts, last_attempt_at) values (?, ?, ?, 1, ?)"
                      server-id player uuid ts]))))

(defn rejected-logins
  "Players refused by SERVER-ID's whitelist, latest first:
  :player_name :uuid :attempts :last_attempt_at."
  [server-id limit]
  (db/run-db-query ["select player_name, uuid, attempts, last_attempt_at from rejected_logins where server_id = ? order by last_attempt_at desc limit ?"
                    server-id limit]))

(defn clear-rejected-login!
  [server-id player]
  (db/exe-db-stmt ["delete from rejected_logins where server_id = ? and lower(player_name) = lower(?)" server-id player]))
