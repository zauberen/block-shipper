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

(ns com.blockshipper.uptime
  "The uptime log: when each server was started, by whom, and for how long."
  (:require [clojure.java.jdbc :as jdbc]
            [com.blockshipper.database.db :as db]
            [com.blockshipper.util :as u]))

(defn open!
  "Record SERVER-ID starting because of STARTED-BY, returning the entry id."
  ([server-id started-by] (open! server-id started-by (u/now-ms)))
  ([server-id started-by ts]
   (let [res (jdbc/insert! (db/get-db-spec) :uptime_log
                           {:server_id server-id :started_at ts :started_by started-by})]
     (-> res first vals first))))

(defn close!
  "Close the uptime entry ID with REASON."
  ([id reason] (close! id reason (u/now-ms)))
  ([id reason ts]
   (db/exe-db-stmt ["update uptime_log set stopped_at = ?, stop_reason = ? where id = ? and stopped_at is null"
                    ts reason id])))

(defn close-dangling!
  "Close entries left open by a previous Block Shipper process that did not shut down cleanly."
  []
  (db/exe-db-stmt ["update uptime_log set stopped_at = started_at, stop_reason = 'unknown (block shipper exited)' where stopped_at is null"]))

(defn entries
  "The most recent LIMIT entries for SERVER-ID with a :duration (ms) added."
  ([server-id limit] (entries server-id limit (u/now-ms)))
  ([server-id limit now]
   (mapv (fn [row] (assoc row :duration (- (or (:stopped_at row) now) (:started_at row))))
         (db/run-db-query ["select * from uptime_log where server_id = ? order by started_at desc limit ?"
                           server-id limit]))))

(defn current
  "The open entry for SERVER-ID, if any."
  [server-id]
  (first (db/run-db-query ["select * from uptime_log where server_id = ? and stopped_at is null order by started_at desc limit 1"
                           server-id])))
