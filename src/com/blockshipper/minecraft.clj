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

(ns com.blockshipper.minecraft
  (:require [com.blockshipper.wrappers.path-utils :as p]
            [com.blockshipper.settings :as s]))

(defn create-directories
  "Create all of the required directories for the application to function."
  []
  (p/create-dir (:minecraft-root-path (s/get-settings)))
  (p/create-dir (:hosting-root (s/get-settings)))
  (p/create-dir (:backup-root (s/get-settings)))
  (p/create-dir (:logs-root (s/get-settings))))
