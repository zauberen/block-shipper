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

(ns com.blockshipper.scheduler
  "Once-a-minute housekeeping: player count samples, idle shutdown of published
  servers, keeping always-active servers up, applying pending updates and running
  scheduled backups. Also the hooks that run when a server stops."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.blockshipper.minecraft.backups :as backups]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.minecraft.updater :as updater]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.stats :as stats]
            [com.blockshipper.util :as u])
  (:import [java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit]))

(def idle-timeout-ms (* 15 60 1000))
(def always-active-retry-ms (* 3 60 1000))

;; Always-active servers an admin stopped by hand; not restarted until started again.
(defonce manual-stops (atom #{}))
;; server-id -> last time the scheduler tried to start it
(defonce auto-starts (atom {}))
;; Servers stopped to apply an update that should come back up afterwards.
(defonce restart-after-update (atom #{}))

(defn idle?
  "True when a running published server has had nobody online for the idle timeout."
  [server inst now]
  (and (= "published" (:host_mode server))
       (= :running (:state inst))
       (empty? (:players inst))
       (>= (- now (:last-activity inst)) idle-timeout-ms)))

(defn- installed? [server] (.exists (io/file (servers/server-dir server) "server.jar")))

(defn- record-stats!
  [all now]
  (doseq [server all :when (not= "disabled" (:host_mode server))]
    (stats/record! (:id server) (count (inst/online-players (:id server))) now))
  (stats/prune! now))

(defn- stop-idle!
  [all now]
  (doseq [server all
          :let [inst (inst/instance (:id server))]
          :when (and inst (idle? server inst now))]
    (log/info "Stopping idle server" (:name server))
    (inst/stop! (:id server) "idle for 15 minutes")))

(defn- keep-always-active!
  [all now]
  (doseq [server all
          :let [id (:id server)]
          :when (and (= "always-active" (:host_mode server))
                     (not (inst/running? id))
                     (not (inst/busy? id))
                     (not (@manual-stops id))
                     (installed? server)
                     (> (- now (get @auto-starts id 0)) always-active-retry-ms))]
    (swap! auto-starts assoc id now)
    (try (inst/start! server "always-active")
         (catch Exception e
           (log/warn "Could not start always-active server" (:name server) ":"
                     (or (:user-message (ex-data e)) (.getMessage e)))))))

(defn- apply-pending-updates!
  [all]
  (when (updater/pending-version)
    (doseq [server all
            :let [inst (inst/instance (:id server))]
            :when (and (= 1 (:pending_update server)) inst
                       (= :running (:state inst)) (empty? (:players inst)))]
      (log/info "Nobody is playing on" (:name server) "- stopping it to update")
      (swap! restart-after-update conj (:id server))
      (inst/stop! (:id server) "stopped to update"))))

(defn tick!
  []
  (try
    (let [all (servers/all-servers)
          now (u/now-ms)]
      (record-stats! all now)
      (stop-idle! all now)
      (keep-always-active! all now)
      (apply-pending-updates! all)
      (backups/run-scheduled!))
    (catch Throwable e
      (log/error e "Scheduler tick failed"))))

(defn on-server-exit
  "Runs after a server process exits: on-stop backup, pending update, restart after update."
  [server reason]
  (let [id (:id server)]
    (when (str/starts-with? (or reason "") "stopped by ")
      (swap! manual-stops conj id))
    (let [backed-up? (when (= 1 (:backup_on_offline server))
                       (try (inst/call-while-busy id #(backups/backup! server "stopped"))
                            true
                            (catch Exception e (log/error e "On-stop backup failed for" (:name server)) false)))
          server (servers/by-id id)
          version (updater/pending-version)]
      (when (and server (= 1 (:pending_update server)) version)
        (try (updater/apply-update! server version backed-up?)
             (catch Exception e (log/error e "Pending update failed for" (:name server)))))
      (when (and server (contains? @restart-after-update id))
        (swap! restart-after-update disj id)
        (try (inst/start! (servers/by-id id) "updater")
             (catch Exception e (log/warn "Could not restart" (:name server) "after update:" (.getMessage e))))))))

(defn install-hooks!
  []
  (inst/add-exit-hook! ::on-exit on-server-exit)
  (inst/add-listener! ::starts (fn [server-id event]
                                 (when (= {:type :state :state :starting} event)
                                   (swap! manual-stops disj server-id)))))

(defonce ^:private executor (atom nil))

(defn start!
  []
  (install-hooks!)
  (when-not @executor
    (let [ex (Executors/newSingleThreadScheduledExecutor
              (reify ThreadFactory
                (newThread [_ r] (doto (Thread. r "scheduler") (.setDaemon true)))))]
      (.scheduleWithFixedDelay ^ScheduledExecutorService ex ^Runnable tick! 1 1 TimeUnit/MINUTES)
      (reset! executor ex)))
  (updater/start!))

(defn stop!
  []
  (when-let [^ScheduledExecutorService ex @executor]
    (.shutdownNow ex)
    (reset! executor nil))
  (updater/stop!))
