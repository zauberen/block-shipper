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

(ns com.blockshipper.test-util
  "Shared fixtures: an isolated settings map, filesystem and sqlite database per test."
  (:require [clojure.java.io :as io]
            [clojure.string]
            [com.blockshipper.database.migration :as m]
            [com.blockshipper.settings :as s]
            [com.blockshipper.users :as users]))

(def ^:dynamic *root* nil)

(defn- delete-recursively
  [f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)]
      (delete-recursively child)))
  (io/delete-file f true))

(defn temp-settings
  "Default settings with every path pointed into ROOT."
  [root]
  (merge (s/get-default-settings)
         {:minecraft-root-path root
          :sqlite-db-path (str root "/settings.db")
          :hosting-root (str root "/hosted")
          :backup-root (str root "/backups")
          :logs-root (str root "/logs")
          :debug false}))

(defn call-with-temp-env
  "Run F with settings, files and database isolated in a fresh temp directory. The
  database starts with an admin account named admin (password admin)."
  [f]
  (let [root (.getCanonicalPath (.toFile (java.nio.file.Files/createTempDirectory
                                          "bs-test"
                                          (into-array java.nio.file.attribute.FileAttribute []))))
        settings (temp-settings root)]
    (reset! m/migration-chk-ran nil)
    (try
      (with-redefs [s/get-settings (constantly settings)
                    s/config-path (constantly (str root "/blockshipper.edn"))]
        (binding [*root* root]
          (users/create! "admin" "admin" "admin")
          (f)))
      (finally
        (reset! m/migration-chk-ran nil)
        (delete-recursively (io/file root))))))

(defmacro with-temp-env
  [& body]
  `(call-with-temp-env (fn [] ~@body)))

(defn temp-env-fixture
  "clojure.test :each fixture wrapping a test in with-temp-env."
  [f]
  (call-with-temp-env f))

(defonce ^:private fake-jar-file (atom nil))

(defn fake-server-jar
  "Compile tests/fixtures/FakeServer.java into an executable jar (once per JVM)."
  []
  (or @fake-jar-file
      (let [out (.toFile (java.nio.file.Files/createTempDirectory
                          "bs-fake" (into-array java.nio.file.attribute.FileAttribute [])))
            compiler (javax.tools.ToolProvider/getSystemJavaCompiler)
            rc (.run compiler nil nil nil (into-array String ["-d" (.getPath out) "tests/fixtures/FakeServer.java"]))
            _ (when-not (zero? rc) (throw (ex-info "Could not compile FakeServer" {})))
            jar (io/file out "fake-server.jar")
            manifest (doto (java.util.jar.Manifest.)
                       (-> .getMainAttributes (.put java.util.jar.Attributes$Name/MANIFEST_VERSION "1.0"))
                       (-> .getMainAttributes (.put java.util.jar.Attributes$Name/MAIN_CLASS "FakeServer")))]
        (with-open [jos (java.util.jar.JarOutputStream. (io/output-stream jar) manifest)]
          (doseq [f (.listFiles out) :when (.endsWith (.getName f) ".class")]
            (.putNextEntry jos (java.util.jar.JarEntry. (.getName f)))
            (io/copy f jos)
            (.closeEntry jos)))
        (reset! fake-jar-file jar))))

(defn free-port
  []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(defn wait-until
  "Poll PRED every 50ms until it is truthy or TIMEOUT-MS passes. Returns the last value."
  ([pred] (wait-until pred 10000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [v (pred)]
         (if (or v (> (System/currentTimeMillis) deadline))
           v
           (do (Thread/sleep 50) (recur))))))))

(def ^:private multipart-boundary "----BlockShipperTestBoundary")

(defn- multipart-body
  "Encode PARTS as multipart/form-data. Values are strings, or
  {:filename s :bytes byte-array} for file fields."
  ^bytes [parts]
  (let [out (java.io.ByteArrayOutputStream.)
        w (fn [^String s] (.write out (.getBytes s "UTF-8")))]
    (doseq [[k v] parts]
      (w (str "--" multipart-boundary "\r\n"))
      (if (map? v)
        (do (w (str "Content-Disposition: form-data; name=\"" (name k) "\"; filename=\"" (:filename v) "\"\r\n"
                    "Content-Type: application/octet-stream\r\n\r\n"))
            (.write out ^bytes (:bytes v)))
        (w (str "Content-Disposition: form-data; name=\"" (name k) "\"\r\n\r\n" v)))
      (w "\r\n"))
    (w (str "--" multipart-boundary "--\r\n"))
    (.toByteArray out)))

(defn request
  "Build a ring request. FORM is a map of form fields sent urlencoded in the body; a
  sequential value sends the field once per element. MULTIPART is sent as
  multipart/form-data instead (see `multipart-body`)."
  [method uri & {:keys [form multipart sid headers query]}]
  (let [body (cond
               multipart (multipart-body multipart)
               form (.getBytes ^String
                               (clojure.string/join "&" (for [[k vs] form
                                                              v (if (sequential? vs) vs [vs])]
                                                          (str (java.net.URLEncoder/encode (name k) "UTF-8") "="
                                                               (java.net.URLEncoder/encode (str v) "UTF-8"))))
                               "UTF-8"))]
    (cond-> {:request-method method
             :uri uri
             :scheme :http
             :server-name "localhost"
             :server-port 8081
             :remote-addr "127.0.0.1"
             :headers (merge {"host" "mc.example.com:8081"}
                             (when sid {"cookie" (str "bs-session-id=" sid)})
                             (when body {"content-type" (if multipart
                                                          (str "multipart/form-data; boundary=" multipart-boundary)
                                                          "application/x-www-form-urlencoded")})
                             headers)}
      query (assoc :query-string query)
      body (assoc :body (java.io.ByteArrayInputStream. ^bytes body)))))

(defn body-str
  [resp]
  (let [b (:body resp)]
    (cond (string? b) b
          (nil? b) ""
          :else (slurp b))))
