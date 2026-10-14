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

(ns com.blockshipper.minecraft.world-import
  "Importing a world from an uploaded zip/jar, tar, tar.gz or 7z archive.

  The archive is extracted into a temporary directory, the shallowest directory
  holding a `level.dat` is taken as the world, and it is moved into the server
  directory as `world`."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [com.blockshipper.minecraft.properties :as props])
  (:import (java.io BufferedInputStream File InputStream)
           (java.nio.file CopyOption Files LinkOption Path StandardCopyOption)
           (java.nio.file.attribute FileAttribute)
           (java.util Comparator)
           (org.apache.commons.compress.archivers ArchiveEntry ArchiveInputStream)
           (org.apache.commons.compress.archivers.sevenz SevenZFile)
           (org.apache.commons.compress.archivers.tar TarArchiveEntry TarArchiveInputStream)
           (org.apache.commons.compress.archivers.zip ZipArchiveEntry ZipFile)
           (org.apache.commons.compress.compressors.gzip GzipCompressorInputStream)))

(def world-dir-name "world")

(def max-extracted-bytes
  "Refuse archives that expand beyond this (guards against zip bombs)."
  (* 32 1024 1024 1024))

(def max-entries 500000)

(def supported-formats "zip, jar, tar, tar.gz or 7z")

(defn- user-error
  [msg]
  (ex-info msg {:user-message msg}))

;; Detection by content rather than file name, so renamed archives still work.

(defn- read-header
  ^bytes [^File f n]
  (with-open [in (io/input-stream f)]
    (let [buf (byte-array n)
          read (.readNBytes in buf 0 n)]
      (java.util.Arrays/copyOf buf read))))

(defn- starts-with?
  [^bytes header offset sig]
  (and (>= (alength header) (+ offset (count sig)))
       (every? true? (map-indexed (fn [i b] (= (unchecked-byte b) (aget header (+ offset i)))) sig))))

(defn- tar-header?
  [^bytes header]
  (starts-with? header 257 (map int "ustar")))

(defn detect-format
  "One of :zip, :tar, :tar-gz, :7z, or nil when FILE is not a supported archive."
  [^File f]
  (let [header (read-header f 512)]
    (cond
      (starts-with? header 0 [0x50 0x4B 0x03 0x04]) :zip
      (starts-with? header 0 [0x50 0x4B 0x05 0x06]) :zip ; empty zip
      (starts-with? header 0 [0x37 0x7A 0xBC 0xAF 0x27 0x1C]) :7z
      (starts-with? header 0 [0x1F 0x8B]) :tar-gz
      (tar-header? header) :tar)))

;; Extraction

(defn- entry-target
  "The file NAME extracts to under DEST, or nil for entries that would escape it."
  ^Path [^Path dest ^String name]
  (let [target (.normalize (.resolve dest (.replace name \\ \/)))]
    (when (and (.startsWith target dest) (not= target dest))
      target)))

(defn- extractor
  "Returns a function (write-entry! name directory? input-stream) that writes
  entries under DEST while enforcing the size and entry limits."
  [^Path dest]
  (let [total (volatile! 0)
        entries (volatile! 0)]
    (fn [^String name directory? ^InputStream in]
      (when (> (vswap! entries inc) max-entries)
        (throw (user-error "The archive has too many files.")))
      (when-some [target (entry-target dest name)]
        (if directory?
          (Files/createDirectories target (make-array FileAttribute 0))
          (do
            (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
            (with-open [out (io/output-stream (.toFile target))]
              (let [buf (byte-array 65536)]
                (loop []
                  (let [n (.read in buf)]
                    (when (pos? n)
                      (when (> (vswap! total + n) max-extracted-bytes)
                        (throw (user-error "The archive is too large once extracted.")))
                      (.write out buf 0 n)
                      (recur))))))))))))

(defn- extract-stream!
  [^ArchiveInputStream in write!]
  (loop []
    (when-some [^ArchiveEntry entry (.getNextEntry in)]
      ;; Links are skipped: they could point outside the extraction directory.
      (when-not (and (instance? TarArchiveEntry entry)
                     (let [^TarArchiveEntry t entry]
                       (or (.isSymbolicLink t) (.isLink t) (not (or (.isFile t) (.isDirectory t))))))
        (when (.canReadEntryData in entry)
          (write! (.getName entry) (.isDirectory entry) in)))
      (recur))))

(defn- extract-zip!
  [^File f write!]
  (with-open [zip (-> (ZipFile/builder) (.setFile f) (.get))]
    (doseq [^ZipArchiveEntry entry (enumeration-seq (.getEntries zip))]
      (when-not (.isUnixSymlink entry)
        (if (.isDirectory entry)
          (write! (.getName entry) true nil)
          (with-open [in (.getInputStream zip entry)]
            (write! (.getName entry) false in)))))))

(defn- extract-7z!
  [^File f write!]
  (with-open [archive (-> (SevenZFile/builder) (.setFile f) (.get))]
    (loop []
      (when-some [entry (.getNextEntry archive)]
        (cond
          (.isDirectory entry) (write! (.getName entry) true nil)
          (.isAntiItem entry) nil
          :else (with-open [in (.getInputStream archive entry)]
                  (write! (.getName entry) false in)))
        (recur)))))

(defn extract!
  "Extract archive FILE into directory DEST. Throws ex-info with :user-message
  for unsupported or damaged archives."
  [^File f ^File dest]
  (let [format (detect-format f)
        write! (extractor (.normalize (.toPath (.getCanonicalFile dest))))]
    (when-not format
      (throw (user-error (str "The world file is not a supported archive (" supported-formats ")."))))
    (try
      (case format
        :zip (extract-zip! f write!)
        :7z (extract-7z! f write!)
        :tar (with-open [in (TarArchiveInputStream. (BufferedInputStream. (io/input-stream f)))]
               (extract-stream! in write!))
        :tar-gz (with-open [in (TarArchiveInputStream.
                                (GzipCompressorInputStream. (BufferedInputStream. (io/input-stream f))))]
                  (extract-stream! in write!)))
      (catch clojure.lang.ExceptionInfo e (throw e))
      (catch Exception e
        (log/warn e "Could not extract world archive")
        (throw (user-error (str "The world archive could not be read: " (.getMessage e))))))))

(defn find-world-root
  "The shallowest directory under DIR (DIR included) that contains level.dat, or nil."
  ^File [^File dir]
  (loop [level [dir]]
    (when (seq level)
      (or (first (filter #(.isFile (io/file % "level.dat")) level))
          (recur (->> level
                      (mapcat #(sort-by (fn [^File c] (.getName c)) (.listFiles ^File %)))
                      (filter #(.isDirectory ^File %))
                      (remove #(= "__MACOSX" (.getName ^File %)))))))))

(defn- delete-tree!
  [^File f]
  (when (.exists f)
    (with-open [paths (Files/walk (.toPath f) (make-array java.nio.file.FileVisitOption 0))]
      (doseq [^Path p (sort (Comparator/reverseOrder) (-> paths .iterator iterator-seq))]
        (Files/deleteIfExists p)))))

(defn- move-tree!
  "Move directory FROM to TO, copying when they are on different filesystems."
  [^File from ^File to]
  (try
    (Files/move (.toPath from) (.toPath to) (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
    (catch java.io.IOException _
      (let [src (.toPath from)
            dst (.toPath to)]
        (with-open [paths (Files/walk src (make-array java.nio.file.FileVisitOption 0))]
          (doseq [^Path p (-> paths .iterator iterator-seq)]
            (let [target (.resolve dst (.relativize src p))]
              (if (Files/isDirectory p (make-array LinkOption 0))
                (Files/createDirectories target (make-array FileAttribute 0))
                (Files/copy p target (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))))
        (delete-tree! from)))))

(defn prepare
  "Extract UPLOAD (a File) into a temporary directory and locate the world in it.
  Returns {:temp-dir File :world File}; call `cleanup!` with it when done.
  Throws ex-info with :user-message when no world can be found."
  [^File upload]
  (let [temp (.toFile (Files/createTempDirectory "bs-world-import" (make-array FileAttribute 0)))]
    (try
      (extract! upload temp)
      (let [world (find-world-root temp)]
        (when-not world
          (throw (user-error "No Minecraft world (a folder with level.dat) was found in the archive.")))
        {:temp-dir temp :world world})
      (catch Exception e
        (delete-tree! temp)
        (throw e)))))

(defn cleanup!
  [{:keys [temp-dir]}]
  (when temp-dir
    (try
      (delete-tree! temp-dir)
      (catch Exception e
        (log/warn e "Could not remove" temp-dir)))))

(defn install!
  "Move the world found by `prepare` into SERVER-DIR as `world` and point
  level-name at it. Any existing world directory is replaced."
  [prepared ^File server-dir]
  (let [target (io/file server-dir world-dir-name)]
    (.mkdirs server-dir)
    (delete-tree! target)
    (move-tree! (:world prepared) target)
    ;; A lock left over from the machine the world was copied from.
    (io/delete-file (io/file target "session.lock") true)
    (props/update-props! server-dir {"level-name" world-dir-name})
    (log/info "Imported world into" (str target))
    target))
