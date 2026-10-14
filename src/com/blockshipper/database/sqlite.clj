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

(ns com.blockshipper.database.sqlite
  (:require [clojure.java.io :as io]
            [com.blockshipper.database.migration :as m]
            [com.blockshipper.settings :as s]
            [com.blockshipper.wrappers.path-utils :as pu]))

(defn db-path
  "Returns the expanded path to the sqlite database file."
  []
  (pu/expand-home-path (:sqlite-db-path (s/get-settings))))

(defn db-spec
  "Returns the db-spec for the configured sqlite database."
  []
  {:classname "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname (db-path)})

(defn initialize
  "Make sure that the DB is ready, running any required migrations."
  []
  (let [db-file (io/file (db-path))]
    (when-let [parent (.getParentFile db-file)]
      (.mkdirs parent))
    (if (not (.exists db-file))
      (m/run-migrations (db-spec))
      (m/check-run-migrations (db-spec)))))
