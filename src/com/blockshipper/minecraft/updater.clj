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

(ns com.blockshipper.minecraft.updater
  "Automatic vanilla updates. A single background thread checks for a new release
  every few hours, caches its jar, and updates servers: offline servers right away,
  running servers are marked pending and updated once nobody is playing (or when a
  published server shuts down)."
  (:require [clojure.tools.logging :as log]
            [com.blockshipper.minecraft.backups :as backups]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.minecraft.mc-update :as mcu]
            [com.blockshipper.servers :as servers])
  (:import [java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit]))

(def check-interval-hours 6)

;; The newest release seen by the last check: {:version v :versions [newest ... oldest]}
(defonce latest (atom nil))

(defn outdated?
  "True when SERVER should move to LATEST-VERSION. Servers on versions that aren't in the
  release list (snapshots, custom jars) are left alone, as are servers on newer versions."
  [server latest-version releases]
  (let [current (:mc_version server)
        idx (into {} (map-indexed (fn [i v] [v i]) releases))]
    (boolean
     (and (= "vanilla" (:server_type server))
          (= 1 (:auto_update server))
          latest-version
          (not= current latest-version)
          (some? current)
          (contains? idx current)
          (contains? idx latest-version)
          (> (idx current) (idx latest-version))))))

(defn apply-update!
  "Install VERSION on the stopped SERVER, backing it up first if configured.
  SKIP-BACKUP? avoids a second backup right after an on-stop backup."
  ([server version] (apply-update! server version false))
  ([server version skip-backup?]
   (inst/call-while-busy
    (:id server)
    (fn []
      (when (and (= 1 (:backup_on_update server)) (not skip-backup?))
        (backups/backup! server (str "before-update-" version)))
      (mcu/install-jar! (mcu/download-server-jar version) (servers/server-dir server))
      (servers/update! (:id server) {:mc_version version :pending_update 0})
      (log/info "Updated" (:name server) "to Minecraft" version)))))

(defn check!
  "Fetch the newest release, cache its jar, and update or queue every outdated server."
  []
  (try
    (let [manifest (mcu/get-manifest)
          version (mcu/latest-release manifest)
          releases (mcu/release-versions manifest)]
      (reset! latest {:version version :versions releases})
      (mcu/download-server-jar version manifest)
      (doseq [server (servers/all-servers)
              :when (outdated? server version releases)]
        (if (inst/running? (:id server))
          (do (log/info "Queueing update of" (:name server) "to" version)
              (servers/update! (:id server) {:pending_update 1}))
          (try (apply-update! server version)
               (catch Exception e
                 (log/error e "Updating" (:name server) "failed"))))))
    (catch Exception e
      (log/warn "Update check failed:" (.getMessage e)))))

(defn pending-version
  "The version a pending update for SERVER should install."
  []
  (:version @latest))

(defonce ^:private executor (atom nil))

(defn start!
  []
  (when-not @executor
    (let [ex (Executors/newSingleThreadScheduledExecutor
              (reify ThreadFactory
                (newThread [_ r] (doto (Thread. r "mc-updater") (.setDaemon true)))))]
      (.scheduleWithFixedDelay ^ScheduledExecutorService ex ^Runnable check! 1 (* 60 check-interval-hours) TimeUnit/MINUTES)
      (reset! executor ex))))

(defn stop!
  []
  (when-let [^ScheduledExecutorService ex @executor]
    (.shutdownNow ex)
    (reset! executor nil)))
