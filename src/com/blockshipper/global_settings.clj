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

(ns com.blockshipper.global-settings
  "Application-wide minecraft settings editable from the UI (the global_settings table)."
  (:require [com.blockshipper.database.db :as db]
            [com.blockshipper.util :as u]))

(def defaults
  {"max_running" "infinite"
   "start_method" "kick"
   "public_host" ""})

(def start-methods ["kick" "hold" "manual"])

(def start-method-options
  "Choices for the start method select, shared by onboarding and settings."
  [["kick" "Kick — start servers when a player joins and ask them to reconnect (proxy)"]
   ["hold" "Hold — start servers when a player joins and keep them waiting until it is up (proxy)"]
   ["manual" "Manual — a Start button on the public dashboard"]])

(defn valid-public-host?
  "True for a host name or IP address without a port."
  [host]
  (boolean (re-matches #"[A-Za-z0-9.-]{1,253}" (or host ""))))

(defn all-settings
  "Every global setting as a map of string key to string value, defaults included."
  []
  (merge defaults
         (into {} (map (juxt :key :value)) (db/run-db-query ["select key, value from global_settings"]))))

(defn get-setting
  [k]
  (get (all-settings) k))

(defn put!
  [k v]
  (db/exe-db-stmt ["insert into global_settings (key, value) values (?, ?) on conflict(key) do update set value = excluded.value"
                   k (str v)]))

(defn max-running
  "Maximum number of concurrently running servers, or nil for no limit."
  []
  (let [v (get-setting "max_running")]
    (when (not= "infinite" v)
      (let [n (u/parse-int v)]
        (when (and n (pos? n)) n)))))

(defn start-method
  []
  (get-setting "start_method"))

(defn auto-start?
  "True when the proxy starts servers on a join attempt (kick or hold)."
  []
  (contains? #{"kick" "hold"} (start-method)))

(defn hold-start?
  "True when a joining player is held until the server is ready instead of kicked."
  []
  (= "hold" (start-method)))

(defn public-host
  []
  (get-setting "public_host"))
