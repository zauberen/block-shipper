(ns com.blockshipper.wrappers.path-utils
  (:require [clojure.java.io :as io]
            [clojure.string])
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

;; File reading functions
(defn read-file-to-string
  "Reads entire file content into a string"
  [path]
  (let [expanded-path (expand-home-path path)]
    (slurp expanded-path)))

(defn read-file-safe
  "Safely reads a file, returns nil if file doesn't exist or can't be read"
  [path]
  (try
    (read-file-to-string path)
    (catch Exception e
      (println (str "Error reading file: " (.getMessage e)))
      nil)))

(defn read-file-lines
  "Reads file into a vector of lines"
  [path]
  (let [expanded-path (expand-home-path path)]
    (-> expanded-path
        slurp
        (clojure.string/split-lines))))

(defn read-file-lines-lazy
  "Reads file lines lazily (good for large files)"
  [path]
  (let [expanded-path (expand-home-path path)]
    (with-open [reader (io/reader expanded-path)]
      (doall (line-seq reader)))))

(defn read-file-with-encoding
  "Reads file with specific encoding (default is UTF-8)"
  [path & {:keys [encoding] :or {encoding "UTF-8"}}]
  (let [expanded-path (expand-home-path path)]
    (slurp expanded-path :encoding encoding)))

;; Directory listing functions
(defn list-directory
  "Lists all files and directories in the given directory path."
  [path]
  (let [expanded-path (expand-home-path path)
        dir (io/file expanded-path)]
    (when (.exists dir)
      (seq (.listFiles dir)))))

(defn list-directories-only
  "Lists only directories (not files) in the given path."
  [path]
  (->> (list-directory path)
       (filter #(.isDirectory %))))

(defn list-directory-names
  "Lists names of all directories in the given directory."
  [path]
  (->> (list-directories-only path)
       (map #(.getName %))))

(defn list-files-only
  "Lists only files (not directories) in the given path."
  [path]
  (->> (list-directory path)
       (filter #(.isFile %))))

(defn list-with-details
  "Lists files/dirs with details like size, type, and full path."
  [path]
  (->> (list-directory path)
       (map (fn [file]
              {:name (.getName file)
               :path (.getAbsolutePath file)
               :type (if (.isDirectory file) :directory :file)
               :size (.length file)
               :last-modified (.lastModified file)}))))

;; File writing functions
(defn write-string-to-file
  "Writes a string to a file, creating directories if needed"
  [path content]
  (let [expanded-path (expand-home-path path)
        file (io/file expanded-path)]
    ;; Create parent directories if they don't exist
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit expanded-path content)))

(defn append-to-file
  "Appends content to a file"
  [path content]
  (let [expanded-path (expand-home-path path)]
    (spit expanded-path content :append true)))
