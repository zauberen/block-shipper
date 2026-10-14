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

(ns com.blockshipper.minecraft.discovery
  "Finds server directories under :hosting-root that have no mc_servers record (copied
  in by hand, or left behind by another install) and registers them."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.blockshipper.minecraft.player-sync :as sync]
            [com.blockshipper.minecraft.properties :as props]
            [com.blockshipper.minecraft.setup :as setup]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.settings :as s]
            [com.blockshipper.wrappers.path-utils :as pu])
  (:import [java.nio.file CopyOption Files]
           [java.util.zip ZipFile]))

(defn hosting-root
  []
  (io/file (pu/expand-home-path (:hosting-root (s/get-settings)))))

(defn jar-version
  "The Minecraft version in JAR's version.json (vanilla 1.14+), or nil."
  [jar]
  (try
    (with-open [zip (ZipFile. (io/file jar))]
      (when-let [entry (.getEntry zip "version.json")]
        (get (json/read-str (slurp (.getInputStream zip entry))) "id")))
    (catch Exception e
      (log/debug "Could not read the version from" (str jar) ":" (.getMessage e))
      nil)))

(defn- instance-dir?
  [dir]
  (or (.exists (io/file dir "server.jar")) (.exists (io/file dir "server.properties"))))

(defn- notes
  "Things an admin should know before importing DIR."
  [dir jar?]
  (cond-> []
    (not jar?)
    (conj "No server.jar. Install a version from the server's settings after importing.")
    (some #(.isDirectory (io/file dir %)) ["mods" "plugins"])
    (conj "Looks modded (mods or plugins folder). Block Shipper runs server.jar as a vanilla server.")))

(defn- registered-dirs
  []
  (set (map #(.getCanonicalPath (servers/server-dir %)) (servers/all-servers))))

(defn- free-port
  "PORT when it is a valid port no server uses yet."
  [port]
  (when (and port (< 0 port 65536) (not ((servers/used-ports) port)))
    port))

(defn- with-suffix
  [base n]
  (let [suffix (str "-" n)]
    (str (subs base 0 (min (count base) (- 32 (count suffix)))) suffix)))

(defn suggest-slug
  "A valid id for FOLDER that isn't in TAKEN: the folder name itself when it is already
  valid, otherwise a slugified version, numbered when needed."
  [folder taken]
  (if (and (servers/valid-slug? folder) (not (taken folder)))
    folder
    (let [base (let [s (servers/slugify folder)] (if (servers/valid-slug? s) s "server"))]
      (first (remove taken (cons base (map #(with-suffix base %) (iterate inc 2))))))))

(defn scan
  "Unregistered server directories under the hosting root, sorted by name:
  {:folder :slug (suggested id) :rename? :version :port :whitelist? :notes [...]}.
  Folders whose name isn't a usable id get a suggested one and are renamed on import."
  []
  (let [root (hosting-root)
        registered (registered-dirs)
        dirs (filter #(.isDirectory %) (.listFiles root))
        candidates (->> dirs
                        (filter #(and (instance-dir? %) (not (registered (.getCanonicalPath %)))))
                        (sort-by #(.getName %)))
        ;; Ids in use, plus every other folder name so a rename never lands on one.
        taken (into (set (map :name (servers/all-servers))) (map #(.getName %)) dirs)]
    (first
     (reduce (fn [[found taken] dir]
               (let [folder (.getName dir)
                     slug (suggest-slug folder (disj taken folder))
                     jar (io/file dir "server.jar")
                     jar? (.exists jar)
                     properties (props/->map (props/read-props dir))]
                 [(conj found {:folder folder
                               :slug slug
                               :rename? (not= folder slug)
                               :version (when jar? (jar-version jar))
                               :port (some-> (get properties "server-port") parse-long free-port)
                               :whitelist? (= "true" (get properties "white-list"))
                               :notes (notes dir jar?)})
                  (conj taken slug)]))
             [[] taken]
             candidates))))

(defn- slug-problem
  "Why SLUG can't be used as the id of FOLDER, or nil."
  [folder slug]
  (let [target (io/file (hosting-root) slug)]
    (cond
      (not (servers/valid-slug? slug))
      "The id must be lowercase letters, numbers, dashes or underscores (max 32)."
      (servers/by-slug slug)
      (str "A server with the id " slug " already exists.")
      (and (not= folder slug) (.exists target)
           (not= (.getCanonicalPath target) (.getCanonicalPath (io/file (hosting-root) folder))))
      (str "A folder named " slug " already exists."))))

(defn- rename-dir!
  [folder slug]
  (try
    (Files/move (.toPath (io/file (hosting-root) folder)) (.toPath (io/file (hosting-root) slug))
                (make-array CopyOption 0))
    (catch Exception e
      (log/warn "Could not rename" folder "to" slug ":" (.getMessage e))
      (throw (ex-info "Rename failed" {:user-message (str folder ": could not rename the folder to " slug ".")})))))

(defn import!
  "Register the unregistered directory FOLDER for USER as SLUG (default: the suggested
  id), renaming the folder when they differ. Returns the new server, or throws ex-info
  with a :user-message. The eula is accepted, Block Shipper's managed properties are
  written, and the directory's whitelist and bans are kept."
  ([folder user] (import! folder nil user))
  ([folder slug user]
   (let [found (first (filter #(= folder (:folder %)) (scan)))
         slug (or (not-empty (some-> slug str/trim)) (:slug found))]
     (when (nil? found)
       (throw (ex-info "Not found" {:user-message (str folder " is not an unregistered server folder.")})))
     (when-let [problem (slug-problem folder slug)]
       (throw (ex-info "Not importable" {:user-message (str folder ": " problem)})))
     (when (not= folder slug)
       (rename-dir! folder slug))
     (let [server (servers/create! (cond-> {:name slug
                                            :display_name folder
                                            :user_id (:id user)
                                            :list_mode (if (:whitelist? found) "whitelist" "blacklist")
                                            ;; Imported worlds may not survive a newer version; opt in from settings.
                                            :auto_update 0}
                                     (:version found) (assoc :mc_version (:version found))
                                     (:port found) (assoc :exposed_port (:port found))))]
       ;; Before anything syncs the (empty) database lists over the files.
       (sync/import-in-game-changes! server)
       (setup/prepare-dir! server)
       (sync/reconcile! server)
       (servers/notify-changed! (:id server))
       (log/info (:username user) "imported server" slug "from" (.getPath (servers/server-dir server))
                 (if (not= folder slug) (str "(renamed from " folder ")") ""))
       server))))
