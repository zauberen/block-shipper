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

(ns com.blockshipper.minecraft.log-parser-test
  (:require [clojure.test :refer [deftest is]]
            [com.blockshipper.minecraft.log-parser :as p]))

(deftest parse-line-test
  (is (= {:type :ready} (p/parse-line "[12:00:00] [Server thread/INFO]: Done (3.456s)! For help, type \"help\"")))
  (is (= {:type :join :player "Steve"} (p/parse-line "[12:00:00] [Server thread/INFO]: Steve joined the game")))
  (is (= {:type :join :player "Steve"}
         (p/parse-line "[12:00:00] [Server thread/INFO]: Steve (formerly known as Bob) joined the game")))
  (is (= {:type :leave :player "Alex_2"} (p/parse-line "[12:00:00] [Server thread/INFO]: Alex_2 left the game")))
  (is (= {:type :join :player "Steve"} (p/parse-line "[12:00:00] [Server thread/INFO]: System chat: Steve joined the game")))
  (is (= {:type :leave :player "Steve"} (p/parse-line "[12:00:00] [Server thread/INFO]: System chat: Steve left the game")))
  (is (= {:type :uuid :player "Steve" :uuid "069a79f4-44e9-4726-a5be-fca90e38aaf5"}
         (p/parse-line "[12:00:00] [User Authenticator #1/INFO]: UUID of player Steve is 069a79f4-44e9-4726-a5be-fca90e38aaf5")))
  (is (= {:type :ready}
         (p/parse-line "[12:00:00] [Server thread/INFO] [minecraft/DedicatedServer]: Done (1.0s)! For help, type \"help\"")))
  (is (= {:type :stopping} (p/parse-line "[12:00:00] [Server thread/INFO]: Stopping the server")))
  (is (nil? (p/parse-line "[12:00:00] [Server thread/INFO]: <Steve> Alex joined the game")))
  (is (nil? (p/parse-line "[12:00:00] [Server thread/INFO]: [Steve: Gave 1 dirt]")))
  (is (nil? (p/parse-line "Steve joined the game"))))

(deftest rejected-login-test
  (is (= {:type :rejected :player "Stranger"}
         (p/parse-line "[12:00:00] [Server thread/INFO]: Disconnecting Stranger (/127.0.0.1:51234): You are not white-listed on this server!")))
  (is (= {:type :rejected :player "Stranger"}
         (p/parse-line "[12:00:00] [Server thread/INFO]: Stranger (/127.0.0.1:51234) lost connection: You are not whitelisted on this server!")))
  (is (= {:type :rejected :player "Old_Timer" :uuid "069a79f4-44e9-4726-a5be-fca90e38aaf5"}
         (p/parse-line (str "[12:00:00] [Server thread/INFO]: Disconnecting com.mojang.authlib.GameProfile@1b2c3d"
                            "[id=069a79f4-44e9-4726-a5be-fca90e38aaf5,name=Old_Timer,properties={},legacy=false]"
                            " (/127.0.0.1:51234): You are not white-listed on this server!"))))
  (is (nil? (p/parse-line "[12:00:00] [Server thread/INFO]: Steve lost connection: You are not white-listed on this server!")))
  (is (nil? (p/parse-line "[12:00:00] [Server thread/INFO]: <Steve> Bob (/1.2.3.4:5) is not whitelisted lol"))))
