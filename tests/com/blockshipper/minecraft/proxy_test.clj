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

(ns com.blockshipper.minecraft.proxy-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockshipper.global-settings :as gs]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.minecraft.instances-test :refer [make-installed-server]]
            [com.blockshipper.minecraft.mc-proxy :as proxy]
            [com.blockshipper.minecraft.player-sync :as sync]
            [com.blockshipper.minecraft.protocol :as proto]
            [com.blockshipper.player-lists :as pl]
            [com.blockshipper.players :as players]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.test-util :as tu])
  (:import [java.io ByteArrayInputStream]
           [java.net Socket]))

(use-fixtures :each
  tu/temp-env-fixture
  (fn [f] (with-redefs [sync/lookup-uuid (constantly nil)]
            (try (f) (finally (proxy/stop-all!) (inst/stop-all! "test cleanup"))))))

(deftest varint-test
  (doseq [n [0 1 127 128 255 25565 2097151 2147483647 -1]]
    (is (= n (proto/read-varint (ByteArrayInputStream. (proto/varint-bytes n)))) n))
  (is (= [0xff 0x01] (map #(bit-and % 0xff) (proto/varint-bytes 255)))))

(deftest handshake-roundtrip-test
  (let [packet (proto/read-packet (ByteArrayInputStream. (proto/handshake-bytes 767 "mc.example.com" 25565 2)))]
    (is (= {:protocol 767 :address "mc.example.com" :port 25565 :next-state 2}
           (proto/parse-handshake packet)))))

(defn- connect [port]
  (doto (Socket. "127.0.0.1" (int port)) (.setSoTimeout 5000)))

(defn- status-ping
  [port]
  (with-open [s (connect port)]
    (let [out (.getOutputStream s) in (.getInputStream s)]
      (proto/write! out (proto/handshake-bytes 767 "localhost" port 1))
      (proto/write! out (proto/packet-bytes 0 (byte-array 0)))
      (let [resp (proto/read-packet in)
            status (json/read-str (proto/read-string* (:body resp)))]
        (proto/write! out (proto/packet-bytes 1 (byte-array 8 (byte 7))))
        (is (= 1 (:id (proto/read-packet in))) "pong")
        status))))

(defn- login
  "Attempt a login and return the disconnect message."
  [port player]
  (with-open [s (connect port)]
    (let [out (.getOutputStream s) in (.getInputStream s)]
      (proto/write! out (proto/handshake-bytes 767 "localhost" port 2))
      (proto/write! out (proto/packet-bytes 0 (proto/string-bytes player)))
      (get (json/read-str (proto/read-string* (:body (proto/read-packet in)))) "text"))))

(defn- proxied-server
  [slug & [attrs]]
  (let [server (make-installed-server slug (merge {:exposed_port (tu/free-port)} attrs))]
    (proxy/sync-listeners!)
    server))

(deftest sleeping-server-starts-on-join-test
  (let [server (proxied-server "alpha")
        port (:exposed_port server)
        id (:id server)]
    (testing "offline servers answer pings themselves"
      (is (str/includes? (get-in (status-ping port) ["description" "text"]) "sleeping")))
    (testing "joining starts the server and asks the player to reconnect"
      (is (str/includes? (login port "Steve") "Reconnect"))
      (is (tu/wait-until #(inst/ready? id)))
      (is (= "proxy:Steve" (:started-by (inst/instance id)))))
    (testing "once ready, traffic is forwarded to the server"
      (with-open [s (connect port)]
        (let [hello (proto/handshake-bytes 767 "localhost" port 2)]
          (proto/write! (.getOutputStream s) hello)
          (proto/write! (.getOutputStream s) (.getBytes "hello"))
          ;; The fake server echoes everything back.
          (let [buf (byte-array (+ (alength hello) 5))]
            (.readFully (java.io.DataInputStream. (.getInputStream s)) buf)
            (is (= (seq (concat hello (.getBytes "hello"))) (seq buf)))))))))

(deftest hold-mode-test
  (gs/put! "start_method" "hold")
  (let [server (proxied-server "alpha")
        port (:exposed_port server)
        id (:id server)]
    (testing "joining starts the server and the held connection is forwarded once it is ready"
      (with-open [s (doto (Socket. "127.0.0.1" (int port)) (.setSoTimeout 30000))]
        (let [hello (proto/handshake-bytes 767 "localhost" port 2)
              login-start (proto/packet-bytes 0 (proto/string-bytes "Steve"))
              out (.getOutputStream s)]
          (proto/write! out hello)
          (proto/write! out login-start)
          ;; The fake server echoes everything back, so the replayed packets arrive first.
          (let [buf (byte-array (+ (alength hello) (alength login-start)))]
            (.readFully (java.io.DataInputStream. (.getInputStream s)) buf)
            (is (= (seq (concat hello login-start)) (seq buf))))
          (is (inst/ready? id))
          (is (= "proxy:Steve" (:started-by (inst/instance id)))))))
    (testing "a server that doesn't come up in time asks the player to reconnect"
      (inst/stop! id "test")
      (is (tu/wait-until #(not (inst/running? id))))
      (with-redefs [proxy/hold-timeout-ms 0]
        (is (str/includes? (login port "Alex") "Reconnect")))))
  (testing "banned players are still turned away"
    (let [server (proxied-server "beta")]
      (pl/add! (:id server) "ban" "Griefer" {})
      (is (str/includes? (login (:exposed_port server) "Griefer") "banned")))))

(deftest blocked-players-test
  (let [server (proxied-server "alpha")
        port (:exposed_port server)]
    (pl/add! (:id server) "ban" "Griefer" {})
    (is (str/includes? (login port "Griefer") "banned"))
    (servers/update! (:id server) {:list_mode "whitelist"})
    (is (str/includes? (login port "Stranger") "not whitelisted"))
    (is (= ["Stranger"] (map :player_name (players/rejected-logins (:id server) 10))))
    (Thread/sleep 300)
    (is (not (inst/running? (:id server))))))

(deftest manual-mode-test
  (gs/put! "start_method" "manual")
  (let [server (proxied-server "alpha")
        port (:exposed_port server)]
    (is (str/includes? (get-in (status-ping port) ["description" "text"]) "offline"))
    (is (str/includes? (login port "Steve") "offline"))
    (Thread/sleep 300)
    (is (not (inst/running? (:id server))))))

(deftest listener-sync-test
  (let [server (proxied-server "alpha")
        old-port (:exposed_port server)
        new-port (tu/free-port)]
    (servers/update! (:id server) {:exposed_port new-port})
    (proxy/sync-listeners!)
    (is (= new-port (:port (get @proxy/listeners (:id server)))))
    (is (thrown? java.net.ConnectException (Socket. "127.0.0.1" (int old-port))))
    (servers/update! (:id server) {:host_mode "disabled"})
    (proxy/sync-listeners!)
    (is (empty? @proxy/listeners))))
