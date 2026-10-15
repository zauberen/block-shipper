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

(ns com.blockshipper.onboarding-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.blockshipper :as bs]
            [com.blockshipper.database.migration :as m]
            [com.blockshipper.global-settings :as gs]
            [com.blockshipper.settings :as s]
            [com.blockshipper.test-util :as tu]
            [com.blockshipper.users :as users]
            [com.blockshipper.web-test :refer [login]]))

(defn- post [uri & {:as opts}]
  (let [resp (bs/app (apply tu/request :post uri (mapcat identity opts)))]
    (assoc resp :text (tu/body-str resp))))

(defn- config-form
  [root]
  {:app-name "My Shipper" :listen-port "8081" :debug "1"
   :minecraft-root-path root :hosting-root (str root "/hosted") :backup-root (str root "/backups")
   :logs-root (str root "/logs") :sqlite-db-path (str root "/chosen.db")
   :mc-mem-min "1G" :mc-mem-max "4G" :java-cmd "/opt/jdk/bin/java" :proxy-bind-address "0.0.0.0"
   :nosql-type "yes-sql" :nosql-host "localhost" :nosql-port "6379"})

(defn- call-with-fresh-install
  "Run F as a first start: no settings file, no database, setup required. Unlike
  `tu/with-temp-env` the real settings code runs, reading a settings file in the temp dir."
  [f]
  (let [root (.getCanonicalPath (.toFile (java.nio.file.Files/createTempDirectory
                                          "bs-setup" (into-array java.nio.file.attribute.FileAttribute []))))
        config (str root "/blockshipper.edn")
        started (atom 0)]
    (reset! m/migration-chk-ran nil)
    (try
      (with-redefs [s/config-path (constantly config)
                    s/get-default-settings (let [defaults (s/get-default-settings)]
                                             (constantly (merge defaults (tu/temp-settings root))))]
        (s/pin-restart-settings!)
        (reset! @#'bs/setup-required true)
        (with-redefs-fn {#'bs/on-startup #(swap! started inc)}
          #(f root config started)))
      (finally
        (reset! @#'bs/setup-required false)
        (s/pin-restart-settings! {})
        (reset! m/migration-chk-ran nil)
        (doseq [file (reverse (file-seq (io/file root)))]
          (io/delete-file file true))))))

(deftest setup-gate-test
  (call-with-fresh-install
   (fn [_root _config _started]
     (is (= "/setup" (get-in (bs/app (tu/request :get "/admin")) [:headers "Location"])))
     (is (= "/setup" (get-in (bs/app (tu/request :get "/login")) [:headers "Location"])))
     (is (= "/setup" (get-in (bs/app (tu/request :get "/" :headers {"hx-request" "true"})) [:headers "HX-Redirect"])))
     (let [page (bs/app (tu/request :get "/setup"))]
       (is (= 200 (:status page)))
       (is (str/includes? (tu/body-str page) "Finish setup")))
     (testing "static files are still served"
       (is (= 200 (:status (bs/app (tu/request :get "/css/setup.css")))))))))

(deftest setup-validation-test
  (call-with-fresh-install
   (fn [root config started]
     (let [form (merge (config-form root) {:username "boss" :password "longenough" :confirm "different"
                                           :start_method "kick" :listen-port "99999"
                                           :hosting-root "relative/path"})
           resp (post "/api/setup" :form form)]
       (is (str/includes? (:text resp) "match"))
       (is (str/includes? (:text resp) "web UI port"))
       (is (str/includes? (:text resp) "absolute path"))
       (testing "what was typed is shown again"
         (is (str/includes? (:text resp) "relative/path")))
       (is (not (.exists (io/file config))))
       (is (zero? @started))))))

(deftest setup-completes-test
  (call-with-fresh-install
   (fn [root config started]
     (let [resp (post "/api/setup" :form (merge (config-form root)
                                                {:username "boss" :password "longenough" :confirm "longenough"
                                                 :start_method "manual" :public_host "play.example.com"}))
           sid (second (re-find #"bs-session-id=([^;]+)" (get-in resp [:headers "Set-Cookie"] "")))]
       (is (= "/admin" (get-in resp [:headers "HX-Redirect"])))
       (testing "the configuration is written"
         (let [saved (edn/read-string (slurp config))]
           (is (= "My Shipper" (:app-name saved)))
           (is (= 8081 (:listen-port saved)))
           (is (= 'yes-sql (:nosql-type saved)))
           (is (= "/opt/jdk/bin/java" (:java-cmd (s/get-settings))))))
       (testing "the admin lives in the chosen database"
         (is (.exists (io/file root "chosen.db")))
         (is (users/check-password "boss" "longenough"))
         (is (= "admin" (:role (users/by-username "boss"))))
         (is (nil? (users/by-username "admin"))))
       (is (= ["manual" "play.example.com"] [(gs/start-method) (gs/public-host)]))
       (is (= 1 @started))
       (testing "the new admin is logged in and the app is open"
         (is (= 200 (:status (bs/app (tu/request :get "/admin" :sid sid))))))
       (testing "setup can't be run twice"
         (is (= "/admin" (get-in (bs/app (tu/request :get "/setup")) [:headers "Location"])))
         (is (= 404 (:status (post "/api/setup" :form {:username "evil"}))))
         (is (nil? (users/by-username "evil"))))))))

(deftest setup-port-change-test
  (call-with-fresh-install
   (fn [root _config _started]
     (let [resp (post "/api/setup" :form (merge (config-form root)
                                                {:listen-port "9090" :username "boss" :password "longenough"
                                                 :confirm "longenough" :start_method "kick"}))]
       (is (str/includes? (:text resp) "port 9090"))
       (is (some? (get-in resp [:headers "Set-Cookie"])))
       (testing "the running port stays until a restart"
         (is (= 8081 (:listen-port (s/get-settings))))
         (is (= [:listen-port] (s/pending-restart))))))))

(deftest server-config-tab-test
  (tu/with-temp-env
    (let [sid (login "admin")
          root tu/*root*]
      (is (= 200 (:status (bs/app (tu/request :get "/admin/settings/server" :sid sid)))))
      (testing "validation"
        (is (str/includes? (:text (post "/api/global/config" :sid sid
                                        :form (assoc (config-form root) :mc-mem-max "lots")))
                           "Memory sizes"))
        (is (str/includes? (:text (post "/api/global/config" :sid sid
                                        :form (assoc (config-form root) :upload-chunk-mb "0")))
                           "upload piece size")))
      (testing "saving writes the file and flags restart-only changes"
        (let [resp (post "/api/global/config" :sid sid
                         :form (assoc (config-form root) :listen-port "9090" :sqlite-db-path (str root "/settings.db")))
              saved (edn/read-string (slurp (s/config-path)))]
          (is (str/includes? (:text resp) "Restart Block Shipper to apply: Web UI port"))
          (is (= 9090 (:listen-port saved)))
          (is (= "4G" (:mc-mem-max saved)))
          (is (true? (:debug saved)))
          (testing "a form without the upload piece size saves the default"
            (is (= 1 (:upload-chunk-mb saved))))))
      (testing "the upload piece size applies without a restart"
        (post "/api/global/config" :sid sid
              :form (assoc (config-form root) :upload-chunk-mb "8" :sqlite-db-path (str root "/settings.db")))
        (is (= 8 (:upload-chunk-mb (edn/read-string (slurp (s/config-path))))))
        (is (not (some #{:upload-chunk-mb} (s/pending-restart)))))
      (testing "moderators can't change it"
        (users/create! "mod" "password1" "moderator")
        (is (= 403 (:status (post "/api/global/config" :sid (login "mod") :form (config-form root)))))
        (is (= 404 (:status (bs/app (tu/request :get "/admin/settings/server" :sid (login "mod"))))))))))
