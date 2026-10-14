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

(ns com.blockshipper.minecraft.mc-update
  "Downloads vanilla server jars from Mojang into a local cache."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [com.blockshipper.settings :as s]
            [com.blockshipper.wrappers.path-utils :as pu])
  (:import [java.nio.file Files StandardCopyOption CopyOption]
           [java.security MessageDigest]))

(def default-manifest-url "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")

(defn manifest-url
  []
  (or (:mc-manifest-url (s/get-settings)) default-manifest-url))

(defn- read-json
  [url]
  (with-open [r (io/reader url)]
    (json/read r :key-fn keyword)))

(defn get-manifest
  []
  (read-json (manifest-url)))

(defonce ^:private manifest-cache (atom nil))

(defn cached-manifest
  "The manifest, refetched at most every 10 minutes (used by the UI)."
  []
  (let [{:keys [at manifest]} @manifest-cache]
    (if (and manifest (< (- (System/currentTimeMillis) at) (* 10 60 1000)))
      manifest
      (let [manifest (get-manifest)]
        (reset! manifest-cache {:at (System/currentTimeMillis) :manifest manifest})
        manifest))))

(defn latest-release
  "The id of the latest release in MANIFEST."
  [manifest]
  (get-in manifest [:latest :release]))

(defn get-latest-release-version
  []
  (latest-release (get-manifest)))

(defn release-versions
  "Release version ids in MANIFEST, newest first."
  [manifest]
  (->> (:versions manifest)
       (filter #(= "release" (:type %)))
       (mapv :id)))

(defn version-entry
  [manifest version-id]
  (first (filter #(= (:id %) version-id) (:versions manifest))))

(defn server-download
  "The {:url :sha1 :size} of the server jar for VERSION-ID, or nil when it has none."
  [manifest version-id]
  (when-let [entry (version-entry manifest version-id)]
    (get-in (read-json (:url entry)) [:downloads :server])))

(defn get-cache-dir
  []
  (let [cache-dir (io/file (pu/expand-home-path (:minecraft-root-path (s/get-settings))) "jars" "vanilla")]
    (.mkdirs cache-dir)
    cache-dir))

(defn cached-jar-file
  [version]
  (io/file (get-cache-dir) (str "server-" version ".jar")))

(defn get-cached-jar
  [version]
  (let [f (cached-jar-file version)]
    (when (.exists f) f)))

(defn sha1-hex
  [file]
  (let [md (MessageDigest/getInstance "SHA-1")
        buf (byte-array 65536)]
    (with-open [in (io/input-stream file)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn download-server-jar
  "Returns the cached jar for VERSION, downloading and verifying it first if needed."
  ([version] (or (get-cached-jar version) (download-server-jar version (get-manifest))))
  ([version manifest]
   (or (get-cached-jar version)
       (let [{:keys [url sha1]} (or (server-download manifest version)
                                    (throw (ex-info (str "No server download for version " version)
                                                    {:user-message (str "Minecraft " version " has no server download.")})))
             target (cached-jar-file version)
             tmp (io/file (.getParentFile target) (str (.getName target) ".part"))]
         (log/info "Downloading Minecraft server" version "from" url)
         (with-open [in (io/input-stream url)
                     out (io/output-stream tmp)]
           (io/copy in out))
         (when (and sha1 (not= sha1 (sha1-hex tmp)))
           (io/delete-file tmp true)
           (throw (ex-info (str "Checksum mismatch downloading " version) {:user-message "Download was corrupted, try again."})))
         (Files/move (.toPath tmp) (.toPath target)
                     (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
         target))))

(defn install-jar!
  "Copy the cached JAR into SERVER-DIR as server.jar."
  [jar server-dir]
  (.mkdirs (io/file server-dir))
  (let [tmp (io/file server-dir "server.jar.part")]
    (io/copy jar tmp)
    (Files/move (.toPath tmp) (.toPath (io/file server-dir "server.jar"))
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
