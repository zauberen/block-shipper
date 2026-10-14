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

(ns com.blockshipper.settings
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [com.blockshipper.wrappers.path-utils :as pu]))

(defn get-default-settings
  "Returns the default settings as a settings object."
  []
  {:app-name "Block Shipper"
   :minecraft-root-path "~/minecraft"
   :db 'sqlite
   :sqlite-db-path "~/minecraft/settings.db"
   :hosting-root "~/minecraft/hosted"
   :backup-root "~/minecraft/backups"
   :logs-root "~/minecraft/logs"
   :mc-mem-max "3G"
   :mc-mem-min "512M"
   :java-cmd "java"
   :proxy-bind-address "0.0.0.0"
   :listen-port 8081
   ;; Also supports valkey/redis. yes-sql means the db is used for sessions.
   :nosql-type 'yes-sql
   :nosql-port 6379
   :nosql-host "localhost"
   ;; Enables /api/echo (admins only), which prints raw requests.
   :debug false})

(defn config-path
  "Where the settings file lives."
  []
  "~/.blockshipper")

(def config-keys
  "Settings written by onboarding and editable under Settings -> Server configuration."
  [:app-name :listen-port :debug
   :minecraft-root-path :hosting-root :backup-root :logs-root :sqlite-db-path
   :mc-mem-min :mc-mem-max :java-cmd :proxy-bind-address
   :nosql-type :nosql-host :nosql-port])

(def restart-keys
  "Settings read once when Block Shipper starts; changing them needs a restart."
  #{:listen-port :logs-root :sqlite-db-path :db :proxy-bind-address :nosql-type :nosql-host :nosql-port})

;; The restart-only settings this process is running with.
(defonce ^:private pinned (atom nil))

(defn configured?
  "True when a settings file exists."
  []
  (pu/file-exists? (config-path)))

(defn- read-config-file
  "The settings file as a map, {} when it doesn't exist."
  []
  (if (configured?)
    (or (edn/read-string (pu/read-file-safe (config-path))) {})
    {}))

(defn file-settings
  "Defaults merged with the settings file, as saved. Restart-only keys may differ from
  what is in effect (see `get-settings`)."
  []
  (try
    ; Set defaults when a setting is nil
    (merge (get-default-settings) (read-config-file))
    (catch Exception e
      (println (str "Error reading " (config-path) ": " (.getMessage e)))
      (.printStackTrace e)
      (get-default-settings))))

(defn get-settings
  "Returns the settings in effect: the settings file, with restart-only keys as they
  were when Block Shipper started."
  []
  (merge (file-settings) @pinned))

(defn pin-restart-settings!
  "Fix the restart-only keys to their values in SETTINGS (default: the settings file)."
  ([] (pin-restart-settings! (file-settings)))
  ([settings] (reset! pinned (select-keys settings restart-keys))))

(defn pending-restart
  "Restart-only keys whose saved value differs from the one in effect."
  []
  (let [saved (file-settings)
        running (get-settings)]
    (filterv #(not= (get saved %) (get running %)) (filter restart-keys config-keys))))

(defn save-settings!
  "Merge UPDATES into the settings file and write it."
  [updates]
  (let [settings (merge (read-config-file) updates)
        file (io/file (pu/expand-home-path (config-path)))
        tmp (io/file (str (.getPath file) ".tmp"))]
    (when-let [parent (.getParentFile file)] (.mkdirs parent))
    (spit tmp (str ";; Block Shipper settings. Edit from Settings -> Server configuration, or by hand\n"
                   ";; while Block Shipper is stopped.\n"
                   (with-out-str (pprint/pprint (into (sorted-map) settings)))))
    (java.nio.file.Files/move (.toPath tmp) (.toPath file)
                              (into-array java.nio.file.CopyOption
                                          [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                           java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
    settings))

(defn get-logfile
  "Returns the log filename as a string. Needs to be used with logs-root to get the full path."
  []
  (let [date (java.time.LocalDate/now)]
    (str (format "%04d%02d%02d"
                 (.getYear date)
                 (.getMonthValue date)
                 (.getDayOfMonth date))
         "-blockshipper.log")))

(defn get-log-path
  "Returns the full expanded path to today's log file."
  []
  (str (pu/expand-home-path (:logs-root (get-settings))) "/" (get-logfile)))

