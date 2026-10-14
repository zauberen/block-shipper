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

(ns com.blockshipper.minecraft.instances-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockshipper.global-settings :as gs]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.minecraft.setup :as setup]
            [com.blockshipper.players :as players]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.test-util :as tu]
            [com.blockshipper.uptime :as uptime]
            [com.blockshipper.users :as users]))

(use-fixtures :each tu/temp-env-fixture)

(defn make-installed-server
  [slug & [attrs]]
  (let [server (servers/create! (merge {:name slug :display_name slug
                                        :user_id (:id (users/by-username "admin"))
                                        :mc_port (tu/free-port) :mem_min "16M" :mem_max "64M"}
                                       attrs))]
    (setup/prepare-dir! server)
    (io/copy (tu/fake-server-jar) (io/file (servers/server-dir server) "server.jar"))
    server))

(deftest start-stop-test
  (let [server (make-installed-server "alpha")
        id (:id server)]
    (is (= {:status :started} (inst/start! server "admin")))
    (is (= {:status :already-running} (inst/start! server "admin")))
    (is (tu/wait-until #(inst/ready? id)))
    (testing "managed properties are written"
      (is (.contains (slurp (io/file (servers/server-dir server) "server.properties")) "server-ip=127.0.0.1")))
    (testing "player events"
      (inst/send-command! id "join Steve")
      (is (tu/wait-until #(contains? (inst/online-players id) "Steve")))
      (is (:online (first (players/summary id))))
      (inst/send-command! id "leave Steve")
      (is (tu/wait-until #(empty? (inst/online-players id)))))
    (testing "console log is buffered"
      (is (some #(.contains % "Done (") (inst/log-lines id)))
      (is (some #{"> join Steve"} (inst/log-lines id))))
    (testing "stopping records uptime"
      (is (true? (deref (inst/stop! id "stopped by admin") 20000 :timeout)))
      (is (= :offline (inst/status id)))
      (let [entry (first (uptime/entries id 1))]
        (is (= "admin" (:started_by entry)))
        (is (= "stopped by admin" (:stop_reason entry)))
        (is (some? (:stopped_at entry)))))))

(deftest crash-and-exit-hooks-test
  (let [server (make-installed-server "alpha")
        id (:id server)
        hook-calls (promise)]
    (inst/add-exit-hook! ::test (fn [s reason] (deliver hook-calls [(:id s) reason])))
    (try
      (inst/start! server "admin")
      (is (tu/wait-until #(inst/ready? id)))
      (inst/send-command! id "join Steve")
      (is (tu/wait-until #(seq (inst/online-players id))))
      (inst/send-command! id "crash")
      (is (= [id "crashed (exit code 3)"] (deref hook-calls 10000 :timeout)))
      (is (not-any? :online (players/summary id)))
      (finally (swap! inst/exit-hooks dissoc ::test)))))

(deftest disabled-server-test
  (let [server (make-installed-server "alpha" {:host_mode "disabled"})]
    (is (thrown? clojure.lang.ExceptionInfo (inst/start! server "admin")))))

(deftest capacity-test
  (gs/put! "max_running" "1")
  (let [a (make-installed-server "alpha")
        b (make-installed-server "beta")
        c (make-installed-server "gamma")]
    (try
      (inst/start! a "admin")
      (is (tu/wait-until #(inst/ready? (:id a))))
      (testing "an idle server is stopped to make room"
        (is (= :queued (:status (inst/start! b "admin"))))
        (is (tu/wait-until #(inst/ready? (:id b)) 20000))
        (is (not (inst/running? (:id a)))))
      (testing "busy servers are not stopped"
        (inst/send-command! (:id b) "join Steve")
        (is (tu/wait-until #(seq (inst/online-players (:id b)))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"capacity" (inst/start! c "admin"))))
      (finally
        (inst/stop-all! "test cleanup")))))

(deftest pick-victim-test
  (is (= 2 (:id (inst/pick-victim [{:id 1 :host-mode "always-active" :players 0 :last-activity 1 :state :running}
                                   {:id 2 :host-mode "published" :players 0 :last-activity 5 :state :running}
                                   {:id 3 :host-mode "published" :players 2 :last-activity 0 :state :running}])))))
