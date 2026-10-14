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

(ns com.blockshipper.scheduler-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockshipper.minecraft.backups :as backups]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.minecraft.instances-test :refer [make-installed-server]]
            [com.blockshipper.minecraft.mc-update :as mcu]
            [com.blockshipper.minecraft.updater :as updater]
            [com.blockshipper.scheduler :as scheduler]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.stats :as stats]
            [com.blockshipper.test-util :as tu])
  (:import [java.time LocalDate]
           [java.util.zip ZipFile]))

(use-fixtures :each
  tu/temp-env-fixture
  (fn [f] (try (f)
               (finally (inst/stop-all! "test cleanup")
                        (reset! updater/latest nil)
                        (reset! scheduler/manual-stops #{})
                        (reset! scheduler/restart-after-update #{})))))

(deftest scheduled-reason-test
  (let [base {:backup_weekly 1 :backup_weekly_day 3 :backup_monthly 0 :backup_monthly_day 1}
        wednesday (LocalDate/of 2026 10 14)]
    (is (= "weekly" (backups/scheduled-reason base wednesday)))
    (is (nil? (backups/scheduled-reason base (LocalDate/of 2026 10 15))))
    (is (nil? (backups/scheduled-reason (assoc base :backup_weekly 0) wednesday)))
    (testing "only once a day"
      (is (nil? (backups/scheduled-reason (assoc base :last_scheduled_backup_at (System/currentTimeMillis))
                                          (LocalDate/now))))))
  (testing "monthly days past the end of the month run on the last day"
    (let [server {:backup_monthly 1 :backup_monthly_day 31}]
      (is (= "monthly" (backups/scheduled-reason server (LocalDate/of 2026 2 28))))
      (is (nil? (backups/scheduled-reason server (LocalDate/of 2026 2 27))))
      (is (= "monthly" (backups/scheduled-reason server (LocalDate/of 2026 3 31)))))))

(deftest backup-name-test
  (is (backups/valid-backup-name? "20261012-120000-weekly.zip"))
  (is (not (backups/valid-backup-name? "../settings.db")))
  (is (not (backups/valid-backup-name? "x/../../y.zip"))))

(deftest offline-backup-test
  (let [server (make-installed-server "alpha")
        dir (servers/server-dir server)]
    (.mkdirs (io/file dir "world"))
    (spit (io/file dir "world" "level.dat") "data")
    (let [f (backups/backup! server "manual")]
      (with-open [zip (ZipFile. f)]
        (let [names (set (map #(.getName %) (enumeration-seq (.entries zip))))]
          (is (contains? names "world/level.dat"))
          (is (contains? names "server.properties"))
          (is (not (contains? names "server.jar"))))))
    (is (= 1 (count (backups/list-backups server))))
    (is (some? (:last_backup_at (servers/by-id (:id server)))))))

(deftest running-backup-test
  (let [server (make-installed-server "alpha")
        id (:id server)]
    (inst/start! server "admin")
    (is (tu/wait-until #(inst/ready? id)))
    (backups/backup! server "manual")
    (is (some #{"> save-off"} (inst/log-lines id)))
    (is (some #{"> save-on"} (inst/log-lines id)))))

(deftest outdated-test
  (let [releases ["1.21.4" "1.21.3" "1.20.1"]
        server {:server_type "vanilla" :auto_update 1 :mc_version "1.21.3"}]
    (is (updater/outdated? server "1.21.4" releases))
    (is (not (updater/outdated? (assoc server :auto_update 0) "1.21.4" releases)))
    (is (not (updater/outdated? (assoc server :mc_version "1.21.4") "1.21.4" releases)))
    (is (not (updater/outdated? (assoc server :mc_version "24w10a") "1.21.4" releases)))
    (is (not (updater/outdated? (assoc server :mc_version "1.21.4") "1.21.3" releases)))))

(defn- fake-manifest []
  {:latest {:release "1.21.4"}
   :versions [{:id "1.21.4" :type "release"} {:id "1.21.3" :type "release"}]})

(deftest updater-check-test
  (with-redefs [mcu/get-manifest fake-manifest
                mcu/download-server-jar (fn [& _] (tu/fake-server-jar))]
    (let [offline (make-installed-server "offline" {:mc_version "1.21.3"})
          running (make-installed-server "running" {:mc_version "1.21.3"})
          pinned (make-installed-server "pinned" {:mc_version "1.21.3" :auto_update 0})]
      (inst/start! running "admin")
      (is (tu/wait-until #(inst/ready? (:id running))))
      (updater/check!)
      (testing "offline servers update immediately, with a backup"
        (is (= "1.21.4" (:mc_version (servers/by-id (:id offline)))))
        (is (= 1 (count (backups/list-backups offline)))))
      (testing "running servers are queued"
        (is (= 1 (:pending_update (servers/by-id (:id running)))))
        (is (= "1.21.3" (:mc_version (servers/by-id (:id running))))))
      (testing "servers without auto update are left alone"
        (is (= "1.21.3" (:mc_version (servers/by-id (:id pinned))))))
      (testing "the pending update is applied once nobody is playing, then the server restarts"
        (scheduler/install-hooks!)
        (scheduler/tick!)
        (is (tu/wait-until #(= "1.21.4" (:mc_version (servers/by-id (:id running)))) 20000))
        (is (tu/wait-until #(inst/ready? (:id running)) 20000))
        (is (= "updater" (:started-by (inst/instance (:id running)))))
        (is (= 0 (:pending_update (servers/by-id (:id running)))))))))

(deftest idle-shutdown-test
  (let [published (make-installed-server "pub")
        always (make-installed-server "always" {:host_mode "always-active"})]
    (scheduler/install-hooks!)
    (testing "always-active servers are started"
      (scheduler/tick!)
      (is (tu/wait-until #(inst/ready? (:id always)))))
    (inst/start! published "admin")
    (is (tu/wait-until #(inst/ready? (:id published))))
    (testing "idle published servers stop; always-active ones keep running"
      (let [old (- (System/currentTimeMillis) scheduler/idle-timeout-ms 1000)]
        (swap! inst/instances #(-> % (assoc-in [(:id published) :last-activity] old)
                                   (assoc-in [(:id always) :last-activity] old))))
      (scheduler/tick!)
      (is (tu/wait-until #(not (inst/running? (:id published))) 20000))
      (is (inst/running? (:id always))))
    (testing "player counts are sampled"
      (is (seq (stats/series (:id always) 0))))
    (testing "an always-active server stopped by hand stays stopped"
      @(inst/stop! (:id always) "stopped by admin")
      (reset! scheduler/auto-starts {})
      (scheduler/tick!)
      (Thread/sleep 300)
      (is (not (inst/running? (:id always)))))))

(deftest backup-on-stop-test
  (let [server (make-installed-server "alpha" {:backup_on_offline 1})]
    (scheduler/install-hooks!)
    (inst/start! server "admin")
    (is (tu/wait-until #(inst/ready? (:id server))))
    @(inst/stop! (:id server) "stopped by admin")
    (is (tu/wait-until #(= 1 (count (backups/list-backups server)))))))
