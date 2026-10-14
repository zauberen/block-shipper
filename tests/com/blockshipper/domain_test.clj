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

(ns com.blockshipper.domain-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockshipper.auth :as auth]
            [com.blockshipper.global-settings :as gs]
            [com.blockshipper.player-lists :as pl]
            [com.blockshipper.players :as players]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.stats :as stats]
            [com.blockshipper.test-util :as tu]
            [com.blockshipper.uptime :as uptime]
            [com.blockshipper.users :as users]))

(use-fixtures :each tu/temp-env-fixture)

(defn- admin [] (users/by-username "admin"))

(defn- make-server
  [slug]
  (servers/create! {:name slug :display_name (str "Server " slug) :user_id (:id (admin))}))

(deftest slug-test
  (is (= "my-cool-server" (servers/slugify "My Cool Server!")))
  (is (servers/valid-slug? "survival_2"))
  (is (not (servers/valid-slug? "../etc")))
  (is (not (servers/valid-slug? "Caps"))))

(deftest server-crud-test
  (let [a (make-server "alpha")
        b (make-server "beta")]
    (testing "ports are allocated without clashes"
      (is (= 25565 (:exposed_port a)))
      (is (= 25566 (:exposed_port b)))
      (is (not= (:mc_port a) (:mc_port b)))
      (is (= #{25565 25566 (:mc_port a) (:mc_port b)} (servers/used-ports))))
    (testing "defaults"
      (is (= "published" (:host_mode a)))
      (is (= 1 (:backup_on_update a)))
      (is (= 0 (:backup_weekly a))))
    (testing "validation"
      (is (seq (servers/validate {:name "alpha"} nil)))
      (is (empty? (servers/validate {:name "alpha"} (:id a))))
      (is (seq (servers/validate {:exposed_port 25566} (:id a))))
      (is (seq (servers/validate {:host_mode "sometimes"} nil))))
    (testing "update and delete"
      (is (= "disabled" (:host_mode (servers/update! (:id a) {:host_mode "disabled" :name "hacked"}))))
      (is (= "alpha" (:name (servers/by-id (:id a)))))
      (servers/delete! (:id a))
      (is (nil? (servers/by-id (:id a)))))))

(deftest users-and-auth-test
  (let [a (make-server "alpha")
        b (make-server "beta")
        mod (users/create! "mod" "password1" "moderator")]
    (is (empty? (users/validate-new "mod2" "password1" "moderator")))
    (is (seq (users/validate-new "mod" "password1" "moderator")))
    (is (seq (users/validate-new "new" "short" "moderator")))
    (users/set-assignments! (:id mod) [(:id a)])
    (let [mod (users/by-username "mod")]
      (is (auth/can-access-server? mod a))
      (is (not (auth/can-access-server? mod b)))
      (is (auth/can-access-server? (admin) b))
      (is (= ["alpha"] (map :name (servers/visible-servers mod))))
      (is (= 2 (count (servers/visible-servers (admin))))))
    (testing "password changes"
      (users/set-password! (:id mod) "newpassword")
      (is (users/check-password "mod" "newpassword"))
      (is (not (users/check-password "mod" "password1"))))
    (testing "last admin protection"
      (is (users/last-admin? (admin)))
      (users/set-role! (:id mod) "admin")
      (is (not (users/last-admin? (admin)))))))

(deftest global-settings-test
  (is (nil? (gs/max-running)))
  (is (= "kick" (gs/start-method)))
  (is (gs/auto-start?))
  (is (not (gs/hold-start?)))
  (gs/put! "start_method" "hold")
  (is (gs/auto-start?))
  (is (gs/hold-start?))
  (gs/put! "max_running" "2")
  (gs/put! "start_method" "manual")
  (is (= 2 (gs/max-running)))
  (is (not (gs/auto-start?))))

(deftest players-test
  (let [{id :id} (make-server "alpha")]
    (players/open-session! id "Steve" nil 1000)
    (players/close-session! id "Steve" 61000)
    (players/open-session! id "Steve" nil 100000)
    (players/open-session! id "Alex" "uuid-a" 120000)
    (players/close-session! id "Alex" 180000)
    (let [summary (into {} (map (juxt :player_name identity)) (players/summary id 160000))]
      (is (= 120000 (:playtime (summary "Steve"))))
      (is (:online (summary "Steve")))
      (is (not (:online (summary "Alex"))))
      (is (= 100000 (:last_login (summary "Steve")))))
    (players/close-all-sessions! id 200000)
    (is (not-any? :online (players/summary id)))
    (is (= ["Alex" "Steve" "Steve"] (map :player_name (players/recent-logins id 10))))))

(deftest uptime-test
  (let [{id :id} (make-server "alpha")
        e1 (uptime/open! id "admin" 1000)]
    (is (= e1 (:id (uptime/current id))))
    (uptime/close! e1 "stopped" 5000)
    (is (nil? (uptime/current id)))
    (uptime/open! id "proxy:Steve" 10000)
    (uptime/close-dangling!)
    (let [[latest first-entry] (uptime/entries id 10)]
      (is (= "proxy:Steve" (:started_by latest)))
      (is (= 4000 (:duration first-entry)))
      (is (= "stopped" (:stop_reason first-entry))))))

(deftest stats-test
  (let [{id :id} (make-server "alpha")]
    (doseq [[t n] [[1000 0] [2000 3] [3000 1]]]
      (stats/record! id n t))
    (is (= [3 1] (map :players (stats/series id 1500))))
    (is (= [{:t 1 :players 5} {:t 3 :players 2}]
           (stats/downsample [{:t 1 :players 5} {:t 2 :players 1} {:t 3 :players 2}] 2)))))

(deftest player-lists-test
  (let [{id :id} (make-server "alpha")]
    (is (pl/add! nil "whitelist" "Steve" {:created-by "admin"}))
    (is (pl/add! id "whitelist" "Alex" {}))
    (is (pl/add! id "whitelist" "steve" {}))
    (is (false? (pl/add! id "whitelist" "Alex" {})))
    (is (= [["Alex" false] ["steve" false]]
           (map (juxt :player_name :inherited) (pl/effective id "whitelist"))))
    (pl/remove! id "whitelist" "STEVE")
    (is (= [["Alex" false] ["Steve" true]]
           (map (juxt :player_name :inherited) (pl/effective id "whitelist"))))
    (is (empty? (pl/effective id "ban")))
    (is (pl/valid-player-name? "Steve_99"))
    (is (not (pl/valid-player-name? "bad name")))))
