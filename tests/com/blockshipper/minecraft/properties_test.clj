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

(ns com.blockshipper.minecraft.properties-test
  (:require [clojure.test :refer [deftest is]]
            [com.blockshipper.minecraft.properties :as props]))

(def sample
  "#Minecraft server properties\n#Sun Oct 12 12:00:00 UTC 2026\nmotd=A Minecraft Server\\u00A7a!\nserver-port=25565\n\nlevel-seed=\nresource-pack-prompt=\\: hi\n")

(deftest parse-test
  (let [m (props/->map (props/parse sample))]
    (is (= "A Minecraft Server§a!" (m "motd")))
    (is (= "25565" (m "server-port")))
    (is (= "" (m "level-seed")))
    (is (= ": hi" (m "resource-pack-prompt")))))

(deftest roundtrip-test
  (let [entries (props/parse sample)
        rendered (props/render entries)]
    (is (= (props/->map entries) (props/->map (props/parse rendered))))
    (is (.startsWith rendered "#Minecraft server properties\n#Sun"))))

(deftest assoc-props-test
  (let [entries (props/assoc-props (props/parse sample) {"server-port" 25600 "pvp" "false"})
        keys-in-order (keep :key entries)]
    (is (= ["motd" "server-port" "level-seed" "resource-pack-prompt" "pvp"] keys-in-order))
    (is (= "25600" ((props/->map entries) "server-port")))))
