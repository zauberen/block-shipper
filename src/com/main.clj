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

(ns com.main
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [com.blockshipper :as bs]
            [com.blockshipper.settings :as s]))

(defn- configure-logging
  []
  (let [log-path (s/get-log-path)]
    (System/setProperty "org.slf4j.simpleLogger.logFile" log-path)
    (System/setProperty "org.slf4j.simpleLogger.showDateTime" "true")
    (System/setProperty "org.slf4j.simpleLogger.dateTimeFormat" "yyyy-MM-dd HH:mm:ss")
    (System/setProperty "org.slf4j.simpleLogger.defaultLogLevel" "info")))

(defn -main [& _]
  (configure-logging)
  (log/info "Block Shipper starting up")
  (bs/start-server))

(defn -stop []
  (bs/stop-server))
