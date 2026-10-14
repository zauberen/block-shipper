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

(ns com.blockshipper.stats
  "Periodic samples of how many players are online per server."
  (:require [com.blockshipper.database.db :as db]
            [com.blockshipper.util :as u]))

(def retention-ms (* 30 24 60 60 1000))

(defn record!
  ([server-id players] (record! server-id players (u/now-ms)))
  ([server-id players ts]
   (db/exe-db-stmt ["insert into server_samples (server_id, players, recorded_at) values (?, ?, ?)"
                    server-id players ts])))

(defn series
  "Samples for SERVER-ID since SINCE (epoch ms), oldest first, as [{:t ms :players n}]."
  [server-id since]
  (mapv (fn [row] {:t (:recorded_at row) :players (:players row)})
        (db/run-db-query ["select players, recorded_at from server_samples where server_id = ? and recorded_at >= ? order by recorded_at"
                          server-id since])))

(defn downsample
  "Reduce SAMPLES to at most N points by taking the max of each bucket, so short peaks still show."
  [samples n]
  (if (<= (count samples) n)
    samples
    (let [size (long (Math/ceil (/ (count samples) (double n))))]
      (mapv (fn [bucket] {:t (:t (first bucket)) :players (apply max (map :players bucket))})
            (partition-all size samples)))))

(defn prune!
  ([] (prune! (u/now-ms)))
  ([now]
   (db/exe-db-stmt ["delete from server_samples where recorded_at < ?" (- now retention-ms)])))
