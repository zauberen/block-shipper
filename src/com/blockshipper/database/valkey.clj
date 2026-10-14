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

(ns com.blockshipper.database.valkey
  (:require [taoensso.carmine :as car]
            [com.blockshipper.settings :as s]))

(defmacro wcar*
  "Macro that reduces the code required to do a nosql operation."
  [& body]
  `(car/wcar {:pool {}
              :spec {:host (:nosql-host (s/get-settings))
                     :port (:nosql-port (s/get-settings))}}
             ~@body))

(defn insert-record-with-ttl
  "Insert a KEY VALUE pair with TTL-SECONDS (time to live in seconds)."
  [key value ttl-seconds]
  (wcar* (car/setex key ttl-seconds value)))

(defn get-record
  "Retrieve value by KEY from nosql server."
  [key]
  (wcar* (car/get key)))

(defn key-exists?
  "Check if a key exists using EXISTS command."
  [key]
  (= 1 (wcar* (car/exists key))))

(defn delete-record
  "Delete a KEY from the nosql server."
  [key]
  (wcar* (car/del key)))
