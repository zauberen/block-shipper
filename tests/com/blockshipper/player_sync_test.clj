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

(ns com.blockshipper.player-sync-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.minecraft.instances-test :refer [make-installed-server]]
            [com.blockshipper.minecraft.player-sync :as sync]
            [com.blockshipper.minecraft.properties :as props]
            [com.blockshipper.player-lists :as pl]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.test-util :as tu]))

(use-fixtures :each tu/temp-env-fixture)

(deftest offline-uuid-test
  ;; Known value for an offline-mode player named Notch
  (is (= "b50ad385-829d-3141-a216-7e7d7539ba7f" (sync/offline-uuid "Notch")))
  (is (= "069a79f4-44e9-4726-a5be-fca90e38aaf5" (sync/dashed-uuid "069a79f444e94726a5befca90e38aaf5"))))

(deftest commands-test
  (is (= ["whitelist add Alex" "whitelist remove Bob" "ban Eve griefing" "pardon Mallory"]
         (sync/commands {:whitelist [{"name" "steve"} {"name" "Bob"}]
                         :ban [{"name" "Mallory"}]}
                        {:whitelist [{:player_name "Steve"} {:player_name "Alex"}]
                         :ban [{:player_name "Eve" :reason "griefing"}]}))))

(deftest desired-inheritance-test
  (with-redefs [sync/lookup-uuid (constantly nil)]
    (let [ban-server (make-installed-server "alpha")
          white-server (make-installed-server "beta" {:list_mode "whitelist"})]
      (pl/add! nil "ban" "Griefer" {})
      (pl/add! nil "whitelist" "Friend" {})
      (testing "ban list servers inherit global bans only"
        (is (= ["Griefer"] (map :player_name (:ban (sync/desired ban-server)))))
        (is (empty? (:whitelist (sync/desired ban-server)))))
      (testing "whitelist servers inherit the global whitelist only"
        (is (= ["Friend"] (map :player_name (:whitelist (sync/desired white-server)))))
        (is (empty? (:ban (sync/desired white-server))))))))

(deftest offline-file-sync-test
  (with-redefs [sync/lookup-uuid (constantly nil)]
    (let [server (make-installed-server "alpha")
          dir (servers/server-dir server)]
      (props/update-props! dir {"online-mode" "false"})
      (sync/add-player! server "ban" "Griefer" {:reason "tnt" :created-by "admin"})
      (let [bans (json/read-str (slurp (io/file dir "banned-players.json")))]
        (is (= [["Griefer" (sync/offline-uuid "Griefer") "tnt"]]
               (map (juxt #(get % "name") #(get % "uuid") #(get % "reason")) bans))))
      (sync/remove-player! server "ban" "Griefer")
      (is (= [] (json/read-str (slurp (io/file dir "banned-players.json"))))))))

(deftest running-sync-and-import-test
  (with-redefs [sync/lookup-uuid (constantly nil)]
    (let [server (make-installed-server "alpha")
          id (:id server)]
      (try
        (inst/start! server "admin")
        (is (tu/wait-until #(inst/ready? id)))
        (sync/add-player! server "ban" "Griefer" {:reason "tnt"})
        (is (tu/wait-until #(some #{"> ban Griefer tnt"} (inst/log-lines id))))
        (testing "in-game bans are imported when the server stops"
          (spit (io/file (servers/server-dir server) "banned-players.json")
                (json/write-str [{"uuid" "u-1" "name" "Griefer"} {"uuid" "u-2" "name" "Spammer" "reason" "spam"}]))
          (sync/import-in-game-changes! server)
          (is (= #{"Griefer" "Spammer"} (set (map :player_name (pl/entries id "ban")))))
          (is (= "u-1" (:uuid (pl/find-entry id "ban" "Griefer")))))
        (finally (inst/stop-all! "cleanup"))))))

(deftest offline-ops-test
  (with-redefs [sync/lookup-uuid (constantly nil)]
    (let [server (make-installed-server "alpha")
          dir (servers/server-dir server)]
      (testing "online-mode servers need a known uuid"
        (is (= :no-uuid (sync/set-op! server "Steve" true nil)))
        (is (= :written (sync/set-op! server "Steve" true "u-1")))
        (is (= [{"uuid" "u-1" "name" "Steve" "level" 4 "bypassesPlayerLimit" false}]
               (json/read-str (slurp (io/file dir "ops.json"))))))
      (testing "offline-mode servers use the offline uuid"
        (props/update-props! dir {"online-mode" "false"})
        (is (= :written (sync/set-op! server "Alex" true nil)))
        (is (= #{"steve" "alex"} (sync/op-names server)))
        (is (= (sync/offline-uuid "Alex") (get (second (sync/read-ops server)) "uuid"))))
      (testing "deop removes the entry"
        (is (= :written (sync/set-op! server "steve" false nil)))
        (is (= #{"alex"} (sync/op-names server)))))))

(deftest running-ops-test
  (let [server (make-installed-server "alpha")
        id (:id server)]
    (try
      (inst/start! server "admin")
      (is (tu/wait-until #(inst/ready? id)))
      (is (= :sent (sync/set-op! server "Steve" true nil)))
      (is (= :sent (sync/set-op! server "Steve" false nil)))
      (is (tu/wait-until #(every? (set (inst/log-lines id)) ["> op Steve" "> deop Steve"])))
      (finally (inst/stop-all! "cleanup")))))
