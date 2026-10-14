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

(ns com.blockshipper.minecraft.setup
  "Prepares a server directory: eula, the properties Block Shipper manages, and installs."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [com.blockshipper.minecraft.properties :as props]
            [com.blockshipper.minecraft.provider :as provider]
            [com.blockshipper.servers :as servers]))

(def managed-keys
  "Properties Block Shipper owns; they are shown read-only in the properties editor."
  #{"server-port" "server-ip" "white-list" "enforce-whitelist"})

(defn managed-properties
  "The server.properties values Block Shipper controls for SERVER. The server only
  listens on localhost; players connect through the Block Shipper proxy."
  [server]
  (let [whitelist? (= "whitelist" (:list_mode server))]
    {"server-port" (str (:mc_port server))
     "server-ip" "127.0.0.1"
     "white-list" (str whitelist?)
     "enforce-whitelist" (str whitelist?)}))

(def default-properties
  {"motd" "A Block Shipper server"})

(defn apply-managed-properties!
  [server]
  (props/update-props! (servers/server-dir server) (managed-properties server)))

(defn prepare-dir!
  "Create SERVER's directory with an accepted eula and initial server.properties."
  [server]
  (let [dir (servers/server-dir server)]
    (.mkdirs dir)
    (spit (io/file dir "eula.txt")
          (str "# Accepted through Block Shipper\neula=true\n"))
    (let [existing (props/->map (props/read-props dir))]
      (props/update-props! dir (merge (apply dissoc default-properties (keys existing))
                                      {"motd" (or (get existing "motd") (servers/display-name server))}
                                      (managed-properties server))))))

;; server-id -> {:state :installing|:done|:failed :message s}
(defonce installs (atom {}))

(defn install-status
  [server-id]
  (get @installs server-id))

(defn install!
  "Download and install VERSION for SERVER, recording progress in `installs`.
  Returns true on success."
  [server version]
  (let [id (:id server)]
    (swap! installs assoc id {:state :installing :message (str "Installing Minecraft " version "…")})
    (try
      (prepare-dir! server)
      (provider/install! (assoc server :dir (servers/server-dir server)) version)
      (servers/update! id {:mc_version version :pending_update 0})
      (swap! installs assoc id {:state :done :message (str "Installed Minecraft " version)})
      true
      (catch Exception e
        (log/error e "Install failed for" (:name server))
        (swap! installs assoc id {:state :failed
                                  :message (or (:user-message (ex-data e))
                                               (str "Install failed: " (.getMessage e)))})
        false))))

(defn install-async!
  "Mark SERVER as installing right away, then install VERSION in the background."
  [server version]
  (swap! installs assoc (:id server) {:state :installing :message (str "Installing Minecraft " version "…")})
  (future (install! server version)))
