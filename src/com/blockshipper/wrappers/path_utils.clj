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

(ns com.blockshipper.wrappers.path-utils
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Paths Files]))

(defn expand-home-path
  "Expands a path that starts with ~ to the user's home directory."
  [path]
  (if (and path (.startsWith path "~"))
    (let [home (System/getProperty "user.home")
          rest-path (subs path 1)]
      (str home rest-path))
    path))

(defn create-dir
  "Creates a directory if it doesn't exist."
  [path-str]
  (let [expanded-path (expand-home-path path-str)
        path (Paths/get expanded-path (into-array String []))]
    (Files/createDirectories path (into-array java.nio.file.attribute.FileAttribute []))
    (.toFile path)))

(defn file-exists?
  "Return true if the file or dir exists."
  [path]
  (let [dir-or-file (io/file (expand-home-path path))]
    (.exists dir-or-file)))

(defn read-file-safe
  "Reads the file at PATH, returns nil if it doesn't exist or can't be read."
  [path]
  (try
    (slurp (expand-home-path path))
    (catch Exception e
      (println (str "Error reading file: " (.getMessage e)))
      nil)))
