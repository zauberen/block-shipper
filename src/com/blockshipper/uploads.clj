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

(ns com.blockshipper.uploads
  "Chunked file uploads (world archives on the create server form).

  The browser announces the file with `start!`, sends it in pieces no larger than
  `chunk-bytes` with `append!` and then submits the form with the upload id; the
  handler claims the finished file with `take!`. Pieces stay under the request body
  limit, so large files also pass reverse proxies with a small body limit (nginx's
  default is 1 MB). Uploads idle for `stale-ms` are deleted."
  (:require [clojure.tools.logging :as log])
  (:import (java.io File FileOutputStream InputStream)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util UUID)))

(def chunk-bytes
  "Largest piece accepted by `append!`; matches the request body limit."
  (* 1024 1024))

(def max-upload-bytes
  "Largest file accepted."
  (* 32 1024 1024 1024))

(def stale-ms
  "Unfinished or unclaimed uploads untouched for this long are deleted."
  (* 60 60 1000))

;; id -> {:file File :filename str :size long :received long :user-id id :touched ms :lock Object}
(defonce ^:private uploads (atom {}))

(defn- user-error
  [msg]
  (ex-info msg {:user-message msg}))

(defn- now [] (System/currentTimeMillis))

(defn- delete!
  [{:keys [^File file]}]
  (when file (.delete file)))

(defn sweep!
  "Delete uploads idle for longer than `stale-ms`."
  []
  (let [cutoff (- (now) stale-ms)
        [old _] (swap-vals! uploads #(into {} (remove (fn [[_ u]] (< (:touched u) cutoff))) %))]
    (doseq [[id u] old
            :when (< (:touched u) cutoff)]
      (log/info "Deleting abandoned upload" id (:filename u))
      (delete! u))))

(defn start!
  "Register an upload of SIZE bytes for USER-ID and return its id."
  [user-id filename size]
  (sweep!)
  (when-not (and (integer? size) (pos? size))
    (throw (user-error "The file is empty.")))
  (when (> size max-upload-bytes)
    (throw (user-error "The file is too large.")))
  (let [id (str (UUID/randomUUID))
        file (.toFile (Files/createTempFile "bs-upload" ".part" (make-array FileAttribute 0)))]
    (swap! uploads assoc id {:file file :filename (str filename) :size size :received 0
                             :user-id user-id :touched (now) :lock (Object.)})
    id))

(defn info
  "The upload with ID if it belongs to USER-ID, else nil."
  [id user-id]
  (let [u (get @uploads id)]
    (when (and u (= user-id (:user-id u)))
      u)))

(defn- read-chunk
  "Up to `chunk-bytes` from IN; throws when IN holds more."
  ^bytes [^InputStream in]
  (let [buf (.readNBytes in (int chunk-bytes))]
    (when (and (= (alength buf) chunk-bytes) (not= -1 (.read in)))
      (throw (user-error "The piece is too large.")))
    buf))

(defn append!
  "Append the bytes of IN at OFFSET to upload ID. Returns the bytes received so far.
  Returns {:conflict received} when OFFSET is not where the upload continues (a
  retried piece that already arrived), so the client can resume from there."
  [id user-id offset ^InputStream in]
  (let [u (or (info id user-id) (throw (user-error "Unknown upload.")))]
    (locking (:lock u)
      ;; Re-read: another request may have appended while this one waited for the lock.
      (let [{:keys [^File file size received]} (or (info id user-id) (throw (user-error "Unknown upload.")))]
        (if (not= offset received)
          {:conflict received}
          (let [buf (read-chunk in)
                total (+ received (alength buf))]
            (when (> total size)
              (throw (user-error "The file is larger than announced.")))
            (with-open [out (FileOutputStream. file true)]
              (.write out buf))
            (swap! uploads update id assoc :received total :touched (now))
            total))))))

(defn take!
  "Claim the finished upload ID of USER-ID: removes it from the registry and returns
  {:file File :filename str}. The caller deletes the file. Throws when the upload is
  unknown or incomplete."
  [id user-id]
  (let [u (info id user-id)]
    (cond
      (nil? u) (throw (user-error "The uploaded world has expired; choose the file again."))
      (< (:received u) (:size u)) (throw (user-error "The world upload didn't finish; choose the file again."))
      :else (do (swap! uploads dissoc id)
                (select-keys u [:file :filename])))))

(defn discard!
  "Delete upload ID of USER-ID, if any."
  [id user-id]
  (when-let [u (info id user-id)]
    (swap! uploads dissoc id)
    (delete! u)))
