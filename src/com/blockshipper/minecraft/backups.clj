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

(ns com.blockshipper.minecraft.backups
  "Zip backups of server directories, and the backup schedule."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.settings :as s]
            [com.blockshipper.util :as u]
            [com.blockshipper.wrappers.path-utils :as pu])
  (:import [java.nio.file Files]
           [java.time DayOfWeek Instant LocalDate LocalDateTime ZoneId]
           [java.time.format DateTimeFormatter]
           [java.util.zip ZipEntry ZipOutputStream]))

(def excluded-files
  "Files that are not worth backing up: the jar can be downloaded again."
  #{"server.jar" "server.jar.part" ".blockshipper.pid"})

(defn backup-dir
  [server]
  (io/file (pu/expand-home-path (:backup-root (s/get-settings))) (:name server)))

(def ^:private stamp-format (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))

(defn backup-file-name
  [reason ^LocalDateTime now]
  (str (.format stamp-format now) "-" (str/replace (or reason "manual") #"[^a-zA-Z0-9._-]+" "_") ".zip"))

(defn valid-backup-name?
  "Guards the download endpoint against path traversal."
  [n]
  (boolean (and (string? n) (re-matches #"[A-Za-z0-9._-]+\.zip" n) (not (str/includes? n "..")))))

(defn- zip-dir!
  [^java.io.File dir ^java.io.File target]
  (let [base (.toPath dir)]
    (with-open [zos (ZipOutputStream. (io/output-stream target))]
      (doseq [^java.io.File f (file-seq dir)
              :when (.isFile f)
              :let [rel (str (.relativize base (.toPath f)))]
              :when (not (excluded-files rel))]
        (try
          (.putNextEntry zos (ZipEntry. (str/replace rel "\\" "/")))
          (Files/copy (.toPath f) zos)
          (.closeEntry zos)
          (catch java.io.IOException e
            ;; e.g. session.lock held by a running server
            (log/warn "Skipping" rel "in backup:" (.getMessage e))))))))

(defn- wait-for-log
  "Wait up to TIMEOUT-MS for SERVER-ID to log a line matching RE."
  [server-id re timeout-ms]
  (let [p (promise)
        k (Object.)]
    (inst/add-listener! k (fn [sid event]
                            (when (and (= sid server-id) (= :log (:type event)) (re-find re (:line event)))
                              (deliver p true))))
    (try
      (deref p timeout-ms false)
      (finally (inst/remove-listener! k)))))

(defn backup!
  "Zip SERVER's directory into its backup folder. Running servers have saving paused
  while the files are copied. Returns the backup file."
  [server reason]
  (let [id (:id server)
        dir (servers/server-dir server)
        out-dir (backup-dir server)
        target (io/file out-dir (backup-file-name reason (LocalDateTime/now)))
        tmp (io/file out-dir (str (.getName target) ".part"))
        running? (inst/ready? id)]
    (.mkdirs out-dir)
    (log/info "Backing up" (:name server) "(" reason ") to" (.getPath target))
    (try
      (when running?
        (inst/send-command! id "save-off")
        (inst/send-command! id "save-all flush")
        (wait-for-log id #"Saved the game" 60000))
      (zip-dir! dir tmp)
      (.renameTo tmp target)
      (servers/update! id {:last_backup_at (u/now-ms)})
      target
      (finally
        (io/delete-file tmp true)
        (when running?
          (inst/send-command! id "save-on"))))))

(defn list-backups
  "SERVER's backups, newest first, as {:name :size :modified}."
  [server]
  (->> (.listFiles (backup-dir server))
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".zip")))
       (map (fn [f] {:name (.getName f) :size (.length f) :modified (.lastModified f)}))
       (sort-by :modified >)
       vec))

(defn backup-file
  "The backup file named NAME for SERVER, or nil when it doesn't exist or the name is invalid."
  [server name]
  (when (valid-backup-name? name)
    (let [f (io/file (backup-dir server) name)]
      (when (.isFile f) f))))

;;; Schedule

(defn- local-date
  [ms]
  (when ms (LocalDate/ofInstant (Instant/ofEpochMilli ms) (ZoneId/systemDefault))))

(defn scheduled-reason
  "The scheduled backup due for SERVER on TODAY (\"weekly\" or \"monthly\"), or nil.
  A schedule runs at most once a day; monthly days past the end of the month run on its last day."
  [server ^LocalDate today]
  (let [last-run (local-date (:last_scheduled_backup_at server))
        already-today? (= last-run today)
        monthly-day (min (or (:backup_monthly_day server) 1) (.lengthOfMonth today))]
    (when-not already-today?
      (cond
        (and (= 1 (:backup_monthly server)) (= monthly-day (.getDayOfMonth today))) "monthly"
        (and (= 1 (:backup_weekly server))
             (= (or (:backup_weekly_day server) 7) (.getValue ^DayOfWeek (.getDayOfWeek today)))) "weekly"))))

(defn run-scheduled!
  "Back up every server whose weekly or monthly backup is due today."
  []
  (let [today (LocalDate/now)]
    (doseq [server (servers/all-servers)
            :let [reason (scheduled-reason server today)]
            :when (and reason (.exists (servers/server-dir server)))]
      (try
        (servers/update! (:id server) {:last_scheduled_backup_at (u/now-ms)})
        (if (inst/running? (:id server))
          (backup! server reason)
          (inst/call-while-busy (:id server) #(backup! server reason)))
        (catch Exception e
          (log/error e "Scheduled backup failed for" (:name server)))))))
