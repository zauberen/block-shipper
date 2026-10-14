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

(ns com.blockshipper.minecraft.world-import-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockshipper.minecraft.properties :as props]
            [com.blockshipper.minecraft.world-import :as wi]
            [com.blockshipper.test-util :as tu])
  (:import (java.io ByteArrayOutputStream File)
           (java.util.zip ZipEntry ZipOutputStream)
           (org.apache.commons.compress.archivers.sevenz SevenZOutputFile)
           (org.apache.commons.compress.archivers.tar TarArchiveEntry TarArchiveOutputStream)
           (org.apache.commons.compress.compressors.gzip GzipCompressorOutputStream)))

(use-fixtures :each tu/temp-env-fixture)

(def world-entries
  "A small world nested inside a folder, as most downloads are."
  {"My World/level.dat" "level"
   "My World/session.lock" "lock"
   "My World/region/r.0.0.mca" "region"
   "My World/DIM-1/region/r.0.0.mca" "nether"
   "readme.txt" "hello"})

(defn- bytes-of [^String s] (.getBytes s "UTF-8"))

(defn zip-bytes
  ^bytes [entries]
  (let [out (ByteArrayOutputStream.)]
    (with-open [zip (ZipOutputStream. out)]
      (doseq [[name content] entries]
        (.putNextEntry zip (ZipEntry. ^String name))
        (.write zip ^bytes (bytes-of content))
        (.closeEntry zip)))
    (.toByteArray out)))

(defn- write-tar
  [out entries]
  (with-open [tar (TarArchiveOutputStream. out)]
    (.setLongFileMode tar TarArchiveOutputStream/LONGFILE_POSIX)
    (doseq [[name content] entries]
      (let [data (bytes-of content)
            entry (doto (TarArchiveEntry. ^String name) (.setSize (alength data)))]
        (.putArchiveEntry tar entry)
        (.write tar data)
        (.closeArchiveEntry tar)))))

(defn- archive-file
  ^File [format entries]
  (let [f (io/file tu/*root* (str "upload-" (name format)))]
    (case format
      :zip (io/copy (zip-bytes entries) f)
      :tar (with-open [out (io/output-stream f)] (write-tar out entries))
      :tar-gz (with-open [out (io/output-stream f)] (write-tar (GzipCompressorOutputStream. out) entries))
      :7z (with-open [sz (SevenZOutputFile. f)]
            (doseq [[name content] entries]
              (let [entry (.createArchiveEntry sz (File. ^String name) ^String name)]
                (.putArchiveEntry sz entry)
                (.write sz ^bytes (bytes-of content))
                (.closeArchiveEntry sz)))))
    f))

(deftest import-each-format-test
  (doseq [format [:zip :tar :tar-gz :7z]]
    (testing (name format)
      (let [upload (archive-file format world-entries)
            server-dir (io/file tu/*root* "hosted" (str "srv-" (name format)))
            _ (is (= format (wi/detect-format upload)))
            prepared (wi/prepare upload)]
        (try
          (is (= "My World" (.getName ^File (:world prepared))))
          (let [world (wi/install! prepared server-dir)]
            (is (= "level" (slurp (io/file world "level.dat"))))
            (is (= "nether" (slurp (io/file world "DIM-1/region/r.0.0.mca"))))
            (is (not (.exists (io/file world "session.lock"))))
            (is (= "world" (get (props/->map (props/read-props server-dir)) "level-name"))))
          (finally
            (wi/cleanup! prepared)))
        (is (not (.exists ^File (:temp-dir prepared))))))))

(deftest world-at-archive-root-test
  (let [prepared (wi/prepare (archive-file :zip {"level.dat" "x" "region/r.0.0.mca" "r"}))]
    (try
      (is (= (:temp-dir prepared) (:world prepared)))
      (finally (wi/cleanup! prepared)))))

(deftest shallowest-world-wins-test
  (let [prepared (wi/prepare (archive-file :zip {"server/world/level.dat" "main"
                                                 "server/world/backups/old/level.dat" "old"}))]
    (try
      (is (= "main" (slurp (io/file (:world prepared) "level.dat"))))
      (finally (wi/cleanup! prepared)))))

(deftest rejects-bad-archives-test
  (testing "no level.dat"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No Minecraft world"
                          (wi/prepare (archive-file :zip {"notes.txt" "x"})))))
  (testing "not an archive"
    (let [f (io/file tu/*root* "plain.txt")]
      (spit f "just text")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a supported archive" (wi/prepare f))))))

(deftest path-traversal-test
  (let [outside (io/file tu/*root* "escaped.txt")
        prepared (wi/prepare (archive-file :zip {"../escaped.txt" "evil"
                                                 "../../escaped.txt" "evil"
                                                 "w/level.dat" "ok"}))]
    (try
      (is (not (.exists outside)))
      (is (= "ok" (slurp (io/file (:world prepared) "level.dat"))))
      (finally (wi/cleanup! prepared)))))
