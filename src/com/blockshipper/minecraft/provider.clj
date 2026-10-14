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

(ns com.blockshipper.minecraft.provider
  "Per server-type behaviour (installing, launching, updating). Only vanilla exists today;
  modded types (e.g. fabric with modrinth packs) add methods here."
  (:require [clojure.java.io :as io]
            [com.blockshipper.minecraft.mc-update :as mcu]
            [com.blockshipper.settings :as s]))

(defn- server-type [server] (keyword (or (:server_type server) "vanilla")))

(defmulti available-versions
  "Installable versions for a server type keyword, newest first."
  identity)

(defmulti latest-version
  "The newest version for a server type keyword."
  identity)

(defmulti install!
  "Install VERSION into SERVER's directory so it can be launched."
  (fn [server _version] (server-type server)))

(defmulti launch-command
  "The command used to start SERVER, run from its directory."
  server-type)

(defmulti auto-update?
  "Whether servers of this type can be updated automatically."
  identity)

(defmethod available-versions :vanilla [_]
  (mcu/release-versions (mcu/cached-manifest)))

(defmethod latest-version :vanilla [_]
  (mcu/get-latest-release-version))

(defmethod install! :vanilla [server version]
  (mcu/install-jar! (mcu/download-server-jar version) (io/file (:dir server))))

(defmethod launch-command :default [server]
  (let [settings (s/get-settings)]
    [(or (:java-cmd settings) "java")
     (str "-Xms" (or (not-empty (:mem_min server)) (:mc-mem-min settings)))
     (str "-Xmx" (or (not-empty (:mem_max server)) (:mc-mem-max settings)))
     "-jar" "server.jar" "nogui"]))

(defmethod auto-update? :vanilla [_] true)
(defmethod auto-update? :default [_] false)
