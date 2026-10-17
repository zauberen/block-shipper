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

(ns com.blockshipper.minecraft.player-import-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockshipper.minecraft.player-import :as pi]
            [com.blockshipper.minecraft.player-sync :as sync]
            [com.blockshipper.minecraft.properties :as props]
            [com.blockshipper.players :as players]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.test-util :as tu]
            [com.blockshipper.users :as users]))

(use-fixtures :each tu/temp-env-fixture)

(def alex "11111111-1111-4111-8111-111111111111")
(def notch "069a79f4-44e9-4726-a5be-fca90e38aaf5")
(def steve "22222222-2222-4222-8222-222222222222")
(def offline (sync/offline-uuid "Ghost"))

(defn make-server
  []
  (let [server (servers/create! {:name "alpha" :display_name "Alpha" :user_id (:id (users/by-username "admin"))})]
    (.mkdirs (servers/server-dir server))
    server))

(defn write-player!
  "Player files in WORLD for UUID, saved at TS, with PLAYTIME-TICKS in the stats (nil for none)."
  [world uuid ts playtime-ticks]
  (let [dat (io/file world "playerdata" (str uuid ".dat"))]
    (io/make-parents dat)
    (spit dat "nbt")
    (.setLastModified dat ts)
    (when playtime-ticks
      (let [stats (io/file world "stats" (str uuid ".json"))]
        (io/make-parents stats)
        (spit stats (json/write-str {"stats" {"minecraft:custom" {"minecraft:play_time" playtime-ticks}}
                                     "DataVersion" 3953}))
        (.setLastModified stats ts)))))

(deftest playtime-test
  (is (= 3600000 (pi/playtime-ms {"stats" {"minecraft:custom" {"minecraft:play_time" 72000}}})))
  (is (= 50 (pi/playtime-ms {"stats" {"minecraft:custom" {"minecraft:play_one_minute" 1}}})))
  (is (= 100 (pi/playtime-ms {"stat.playOneMinute" 2})))
  (is (nil? (pi/playtime-ms {"stats" {}}))))

(deftest import-test
  (let [server (make-server)
        dir (servers/server-dir server)
        id (:id server)
        hour (* 60 60 1000)
        lookups (atom [])]
    (props/update-props! dir {"level-name" "realm"})
    (let [world (io/file dir "realm")]
      (write-player! world alex 1700000000000 72000)
      (write-player! world notch 1700000100000 nil)
      (write-player! world steve 1700000200000 (* 2 72000))
      (write-player! world offline 1700000300000 20)
      (spit (io/file world "playerdata" (str alex ".dat_old")) "old")
      (spit (io/file dir "usercache.json") (json/write-str [{"name" "Alex" "uuid" alex "expiresOn" "x"}
                                                         {"name" "Steve" "uuid" steve "expiresOn" "x"}])))
    ;; Steve played 10 minutes since Block Shipper took over; the world counts 2 hours.
    (players/open-session! id "Steve" nil 1800000000000)
    (players/close-session! id "Steve" 1800000600000)
    (with-redefs [sync/lookup-name (fn [uuid] (swap! lookups conj uuid) (when (= notch uuid) "Notch"))]
      (is (= {:found 4 :imported 2 :updated 1 :unnamed 1} (pi/import! server)))
      (is (= [notch] @lookups) "only unknown online-mode uuids are looked up")
      (let [summary (into {} (map (juxt :player_name identity)) (players/summary id))]
        (is (= #{"Alex" "Notch" "Steve"} (set (keys summary))))
        (is (= hour (:playtime (summary "Alex"))))
        (is (= 1700000000000 (:last_login (summary "Alex"))))
        (is (= alex (:uuid (summary "Alex"))))
        (is (= 0 (:playtime (summary "Notch"))))
        (is (= (* 2 hour) (:playtime (summary "Steve"))))
        (is (= 1800000000000 (:last_login (summary "Steve"))))
        (is (= steve (:uuid (summary "Steve")))))
      (testing "importing again changes nothing"
        (reset! lookups [])
        (is (= {:found 4 :imported 0 :updated 0 :unnamed 1} (pi/import! server)))
        (is (empty? @lookups) "known players need no lookup")
        (is (= 3 (count (players/summary id))))))))

(deftest players-layout-test
  (testing "worlds saved by Minecraft 26.1+ keep player files under players/"
    (let [server (make-server)
          world (io/file (servers/server-dir server) "world")
          dat (io/file world "players" "data" (str alex ".dat"))
          stats (io/file world "players" "stats" (str alex ".json"))]
      (io/make-parents dat)
      (io/make-parents stats)
      (spit dat "nbt")
      (spit stats (json/write-str {"stats" {"minecraft:custom" {"minecraft:play_time" 72000}}}))
      (.setLastModified dat 1700000000000)
      (.setLastModified stats 1700000000000)
      ;; A leftover from before the upgrade: older and with less playtime.
      (write-player! world alex 1600000000000 20)
      (is (= [{:uuid alex :last-seen 1700000000000 :playtime 3600000}] (pi/world-players server))))))

(deftest no-world-test
  (is (= {:found 0 :imported 0 :updated 0 :unnamed 0} (pi/import! (make-server)))))
