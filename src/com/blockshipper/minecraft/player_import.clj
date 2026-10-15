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

(ns com.blockshipper.minecraft.player-import
  "Creates player entries from a world's player files, for players who joined before
  Block Shipper ran the server (an imported world or server folder).

  Each player has <world>/playerdata/<uuid>.dat and usually <world>/stats/<uuid>.json,
  which holds their total playtime. Neither has the player's name: that comes from the
  server's usercache.json and player lists, or the Mojang API for online-mode uuids."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.blockshipper.minecraft.player-sync :as sync]
            [com.blockshipper.minecraft.properties :as props]
            [com.blockshipper.player-lists :as pl]
            [com.blockshipper.players :as players]
            [com.blockshipper.servers :as servers]))

(def ^:private uuid-re #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(def max-lookups
  "Names resolved with the Mojang API per import; each lookup can take a few seconds."
  50)

(def top-up-threshold-ms
  "A known player gets an imported session when the world counts this much more
  playtime than Block Shipper recorded."
  (* 5 60 1000))

(defn world-dir
  [server]
  (let [dir (servers/server-dir server)]
    (io/file dir (or (not-empty (get (props/->map (props/read-props dir)) "level-name")) "world"))))

(defn- read-json
  [f]
  (when (.isFile (io/file f))
    (try (json/read-str (slurp f :encoding "UTF-8"))
         (catch Exception e
           (log/warn "Could not parse" (str f) ":" (.getMessage e))
           nil))))

(defn playtime-ms
  "Total playtime in a stats json DATA (ticks, so 50 ms each), or nil."
  [data]
  (let [custom (get-in data ["stats" "minecraft:custom"])
        ticks (or (get custom "minecraft:play_time")      ; 1.17+
                  (get custom "minecraft:play_one_minute") ; 1.13-1.16, also ticks despite the name
                  (get data "stat.playOneMinute"))]        ; before 1.13
    (when (number? ticks) (* 50 (long ticks)))))

(defn- uuid-files
  "{uuid file} for files in DIR named <uuid><EXT>."
  [dir ext]
  (into {}
        (for [^java.io.File f (.listFiles (io/file dir))
              :let [n (.getName f)]
              :when (and (.isFile f) (str/ends-with? n ext))
              :let [uuid (str/lower-case (subs n 0 (- (count n) (count ext))))]
              :when (re-matches uuid-re uuid)]
          [uuid f])))

(defn world-players
  "Players with data in SERVER's world: [{:uuid :last-seen (ms) :playtime (ms)}].
  Last seen is when their files were last saved, which happens when they leave."
  [server]
  (let [world (world-dir server)
        data (uuid-files (io/file world "playerdata") ".dat")
        stats (uuid-files (io/file world "stats") ".json")]
    (vec (for [uuid (sort (distinct (concat (keys data) (keys stats))))
               :let [files (keep #(get % uuid) [data stats])]]
           {:uuid uuid
            :last-seen (apply max (map #(.lastModified ^java.io.File %) files))
            :playtime (or (some-> (get stats uuid) read-json playtime-ms) 0)}))))

(defn known-names
  "{lower case uuid name} from everything that pairs SERVER's players with their uuid."
  [server]
  (let [dir (servers/server-dir server)
        from-files (for [f ["banned-players.json" "whitelist.json" "ops.json" "usercache.json"]
                         e (let [v (read-json (io/file dir f))] (when (sequential? v) v))
                         :when (map? e)]
                     [(get e "uuid") (get e "name")])
        from-db (for [e (concat (pl/entries nil "whitelist") (pl/entries nil "ban")
                                (pl/entries (:id server) "whitelist") (pl/entries (:id server) "ban")
                                (players/summary (:id server)))]
                  [(:uuid e) (:player_name e)])]
    ;; Later sources win: the usercache is the server's latest view of names.
    (into {} (for [[uuid n] (concat from-db from-files)
                   :when (and (string? uuid) (pl/valid-player-name? n))]
               [(str/lower-case uuid) n]))))

(defn- online-uuid?
  "Mojang accounts have version 4 uuids; offline-mode servers make version 3 ones."
  [uuid]
  (= \4 (nth uuid 14)))

(defn import!
  "Create player entries for the players in SERVER's world. Players Block Shipper
  already knows get their uuid filled in, plus an imported session for playtime the
  world has that was not recorded. Returns counts:
  {:found :imported :updated :unnamed}."
  [server]
  (let [id (:id server)
        known (players/summary id)
        by-uuid (into {} (keep #(when (:uuid %) [(str/lower-case (:uuid %)) %])) known)
        by-name (into {} (map (juxt (comp str/lower-case :player_name) identity)) known)
        names (known-names server)
        lookups (atom 0)
        lookup (fn [uuid]
                 (when (and (online-uuid? uuid) (< @lookups max-lookups))
                   (swap! lookups inc)
                   (let [n (sync/lookup-name uuid)]
                     (when (pl/valid-player-name? n) n))))
        found (world-players server)
        outcomes (for [{:keys [uuid last-seen playtime]} found]
                   (let [player (or (:player_name (by-uuid uuid)) (names uuid) (lookup uuid))
                         existing (or (by-uuid uuid) (some-> player str/lower-case by-name))]
                     (cond
                       (nil? player) :unnamed
                       (nil? existing) (do (players/import-session! id player uuid last-seen playtime)
                                           :imported)
                       :else (let [missing (- playtime (or (:playtime existing) 0))]
                               (when-not (:uuid existing)
                                 (players/set-uuid! id (:player_name existing) uuid))
                               (if (> missing top-up-threshold-ms)
                                 (do (players/import-session! id (:player_name existing) uuid
                                                              (:first_joined existing) missing)
                                     :updated)
                                 :unchanged)))))
        counts (frequencies (doall outcomes))]
    (log/info "Imported players from the world of" (:name server) ":" counts)
    {:found (count found)
     :imported (get counts :imported 0)
     :updated (get counts :updated 0)
     :unnamed (get counts :unnamed 0)}))
