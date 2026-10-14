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

(ns com.blockshipper.util
  (:require [clojure.string]))

(defn now-ms
  "Current time as epoch milliseconds."
  []
  (System/currentTimeMillis))

(defn format-ts
  "Format epoch millis TS in the local time zone, or a dash when nil."
  [ts]
  (if ts
    (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")
             (java.time.LocalDateTime/ofInstant (java.time.Instant/ofEpochMilli ts)
                                                (java.time.ZoneId/systemDefault)))
    "—"))

(defn format-duration
  "Format a duration in milliseconds as e.g. 3d 4h, 2h 5m or 7m."
  [ms]
  (if (nil? ms)
    "—"
    (let [mins (quot ms 60000)
          days (quot mins 1440)
          hours (quot (mod mins 1440) 60)
          minutes (mod mins 60)]
      (cond
        (pos? days) (str days "d " hours "h")
        (pos? hours) (str hours "h " minutes "m")
        :else (str minutes "m")))))

(defn parse-int
  "Parse S as a long, returning DEFAULT (nil) when it is blank or invalid."
  ([s] (parse-int s nil))
  ([s default]
   (if (number? s)
     (long s)
     (or (some-> s str clojure.string/trim parse-long) default))))

(defn checkbox->int
  "HTML checkboxes send a value only when checked."
  [v]
  (if (and v (not= v "") (not= v "0") (not= v "false")) 1 0))
