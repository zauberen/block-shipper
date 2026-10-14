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

(ns com.blockshipper.minecraft.player-sync
  "Keeps a server's whitelist.json and banned-players.json in line with the lists in
  the database (server entries plus the inherited global list).

  Offline servers get their files rewritten. Running servers get console commands for
  the difference, since they own their files while running. When a server stops,
  changes made in-game (bans, pardons, whitelist edits) are imported back."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.minecraft.properties :as props]
            [com.blockshipper.player-lists :as pl]
            [com.blockshipper.servers :as servers])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util UUID]))

;;; UUIDs

(defn dashed-uuid
  "Insert dashes into a 32 character hex uuid."
  [s]
  (if (and s (= 32 (count s)))
    (str (subs s 0 8) "-" (subs s 8 12) "-" (subs s 12 16) "-" (subs s 16 20) "-" (subs s 20))
    s))

(defn offline-uuid
  "The uuid an offline-mode server assigns to NAME."
  [name]
  (str (UUID/nameUUIDFromBytes (.getBytes (str "OfflinePlayer:" name) StandardCharsets/UTF_8))))

(def ^:private http-client
  (delay (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 3)) .build)))

(defn lookup-uuid
  "Resolve NAME's uuid with the Mojang API. Returns nil when it can't be resolved."
  [name]
  (try
    (let [req (-> (HttpRequest/newBuilder (URI. (str "https://api.mojang.com/users/profiles/minecraft/" name)))
                  (.timeout (Duration/ofSeconds 5))
                  .build)
          resp (.send ^HttpClient @http-client req (HttpResponse$BodyHandlers/ofString))]
      (when (= 200 (.statusCode resp))
        (dashed-uuid (get (json/read-str (.body resp)) "id"))))
    (catch Exception e
      (log/debug "UUID lookup failed for" name ":" (.getMessage e))
      nil)))

(defn online-mode?
  [server]
  (not= "false" (get (props/->map (props/read-props (servers/server-dir server))) "online-mode")))

;;; Desired state

(defn desired
  "The lists that should apply to SERVER. Servers inherit the global list matching
  their list mode: whitelist servers inherit the global whitelist, ban list servers
  inherit the global ban list."
  [server]
  (let [id (:id server)
        whitelist? (= "whitelist" (:list_mode server))]
    {:whitelist (if whitelist? (pl/effective id "whitelist") (pl/entries id "whitelist"))
     :ban (if whitelist? (pl/entries id "ban") (pl/effective id "ban"))}))

;;; Files

(def ^:private file-names {:whitelist "whitelist.json" :ban "banned-players.json"})

(defn- list-file [server k] (io/file (servers/server-dir server) (file-names k)))

(defn read-list-file
  "Entries in SERVER's json file for list K, as maps with string keys."
  [server k]
  (let [f (list-file server k)]
    (if (.exists f)
      (try (json/read-str (slurp f :encoding "UTF-8"))
           (catch Exception e
             (log/warn "Could not parse" (.getPath f) ":" (.getMessage e))
             []))
      [])))

(defn- names-of
  [entries k]
  (set (map (comp str/lower-case #(get % k)) entries)))

(def ^:private ban-date-format (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss Z"))

(defn- entry-uuid
  [server entry]
  (or (:uuid entry)
      (when-not (online-mode? server) (offline-uuid (:player_name entry)))))

(defn render-files
  "The json data to write for SERVER's DESIRED lists. Entries without a uuid are left
  out; they are added by command when the server next starts."
  [server desired]
  {:whitelist (vec (for [e (:whitelist desired)
                         :let [uuid (entry-uuid server e)] :when uuid]
                     {"uuid" uuid "name" (:player_name e)}))
   :ban (vec (for [e (:ban desired)
                   :let [uuid (entry-uuid server e)] :when uuid]
               {"uuid" uuid
                "name" (:player_name e)
                "created" (.format ban-date-format (ZonedDateTime/now))
                "source" (or (:created_by e) "Block Shipper")
                "expires" "forever"
                "reason" (or (not-empty (:reason e)) "Banned by an operator.")}))})

(defn write-files!
  [server]
  (let [files (render-files server (desired server))]
    (.mkdirs (servers/server-dir server))
    (doseq [[k data] files]
      (spit (list-file server k) (json/write-str data {:escape-slash false}) :encoding "UTF-8"))))

(defn commands
  "Console commands that turn the server's CURRENT file entries into DESIRED."
  [current desired]
  (let [have-white (names-of (:whitelist current) "name")
        have-ban (names-of (:ban current) "name")
        want-white (set (map (comp str/lower-case :player_name) (:whitelist desired)))
        want-ban (set (map (comp str/lower-case :player_name) (:ban desired)))]
    (concat
     (for [e (:whitelist desired) :when (not (have-white (str/lower-case (:player_name e))))]
       (str "whitelist add " (:player_name e)))
     (for [e (:whitelist current) :when (not (want-white (str/lower-case (get e "name"))))]
       (str "whitelist remove " (get e "name")))
     (for [e (:ban desired) :when (not (have-ban (str/lower-case (:player_name e))))]
       (str/trim (str "ban " (:player_name e) " " (or (:reason e) ""))))
     (for [e (:ban current) :when (not (want-ban (str/lower-case (get e "name"))))]
       (str "pardon " (get e "name"))))))

(defn reconcile!
  "Bring SERVER's lists in line with the database."
  [server]
  (try
    (if (inst/running? (:id server))
      (let [current {:whitelist (read-list-file server :whitelist)
                     :ban (read-list-file server :ban)}]
        (doseq [cmd (commands current (desired server))]
          (inst/send-command! (:id server) cmd)))
      (write-files! server))
    (catch Exception e
      (log/error e "Could not sync player lists for" (:name server)))))

(defn reconcile-all!
  "Re-sync every server, e.g. after the global lists changed."
  []
  (doseq [server (servers/all-servers)]
    (reconcile! server)))

(defn import-in-game-changes!
  "After SERVER stops, record whitelist/ban changes made from the game or console
  as server specific entries, so they are not lost on the next sync."
  [server]
  (let [id (:id server)
        want (desired server)]
    (doseq [[k list-type] [[:whitelist "whitelist"] [:ban "ban"]]]
      (let [file-entries (read-list-file server k)
            file-names (names-of file-entries "name")
            wanted (set (map (comp str/lower-case :player_name) (get want k)))]
        ;; The server resolves uuids for players we added by name; remember them.
        (doseq [e file-entries :when (get e "uuid")]
          (pl/set-uuid! (get e "name") (get e "uuid")))
        (doseq [e file-entries :when (not (wanted (str/lower-case (get e "name"))))]
          (log/info "Importing in-game" list-type "entry" (get e "name") "for" (:name server))
          (pl/add! id list-type (get e "name") {:uuid (get e "uuid") :reason (get e "reason") :created-by "in-game"}))
        ;; Removed in-game: drop the server specific entry (global entries are re-applied).
        (when (.exists (list-file server k))
          (doseq [e (pl/entries id list-type)
                  :when (and (not (file-names (str/lower-case (:player_name e))))
                             (or (:uuid e) (not (online-mode? server))))]
            (log/info "Removing" list-type "entry" (:player_name e) "removed in-game on" (:name server))
            (pl/remove! id list-type (:player_name e))))))))

(defn add-player!
  "Add PLAYER to LIST-TYPE for SERVER (nil for the global list) and sync."
  [server list-type player opts]
  (let [added (pl/add! (:id server) list-type player (assoc opts :uuid (or (:uuid opts) (lookup-uuid player))))]
    (if server (reconcile! server) (reconcile-all!))
    added))

(defn remove-player!
  [server list-type player]
  (pl/remove! (:id server) list-type player)
  (if server (reconcile! server) (reconcile-all!)))

;;; Operators

;; ops.json belongs to the server, so it is not mirrored in the database: running
;; servers get op/deop commands and offline servers have the file edited directly.

(defn- ops-file [server] (io/file (servers/server-dir server) "ops.json"))

(defn read-ops
  "Entries in SERVER's ops.json, as maps with string keys."
  [server]
  (let [f (ops-file server)]
    (if (.exists f)
      (try (json/read-str (slurp f :encoding "UTF-8"))
           (catch Exception e
             (log/warn "Could not parse" (.getPath f) ":" (.getMessage e))
             []))
      [])))

(defn op-names
  "Lower case names of SERVER's operators."
  [server]
  (names-of (read-ops server) "name"))

(defn- write-ops!
  [server entries]
  (.mkdirs (servers/server-dir server))
  (spit (ops-file server) (json/write-str (vec entries) {:escape-slash false}) :encoding "UTF-8"))

(defn set-op!
  "Make PLAYER an operator of SERVER (OP? true) or remove them (false). UUID is the
  player's known uuid, if any. Returns :sent when a command went to the running
  server, :written when ops.json was edited, or :no-uuid when the player's uuid
  could not be resolved for an offline server."
  [server player op? uuid]
  (let [k (str/lower-case player)
        others (remove #(= k (str/lower-case (get % "name" ""))) (read-ops server))]
    (cond
      (inst/send-command! (:id server) (str (if op? "op " "deop ") player)) :sent
      (not op?) (do (write-ops! server others) :written)
      :else (if-let [uuid (or uuid (entry-uuid server {:player_name player}) (lookup-uuid player))]
              (do (write-ops! server (conj (vec others) {"uuid" uuid "name" player
                                                         "level" 4 "bypassesPlayerLimit" false}))
                  :written)
              :no-uuid))))

(defn install-hooks!
  "Sync lists when servers become ready and import in-game edits when they stop."
  []
  (inst/add-listener! ::ready (fn [server-id event]
                                (when (and (= :state (:type event)) (= :running (:state event)))
                                  (when-let [server (servers/by-id server-id)]
                                    (future (reconcile! server))))))
  (inst/add-exit-hook! ::import (fn [server _reason]
                                  (import-in-game-changes! server)
                                  (reconcile! server))))
