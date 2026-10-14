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

(ns com.blockshipper.minecraft.discovery-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockshipper :as bs]
            [com.blockshipper.api.session :as session]
            [com.blockshipper.minecraft.discovery :as discovery]
            [com.blockshipper.minecraft.instances-test :refer [make-installed-server]]
            [com.blockshipper.minecraft.player-sync :as sync]
            [com.blockshipper.minecraft.properties :as props]
            [com.blockshipper.player-lists :as pl]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.test-util :as tu]
            [com.blockshipper.users :as users])
  (:import [java.util.zip ZipEntry ZipOutputStream]))

(use-fixtures :each tu/temp-env-fixture)

(defn- write-jar!
  [file version]
  (with-open [zos (ZipOutputStream. (io/output-stream file))]
    (.putNextEntry zos (ZipEntry. "version.json"))
    (.write zos (.getBytes (json/write-str {"id" version "name" version})))
    (.closeEntry zos)))

(defn- make-dir!
  [slug files]
  (let [dir (io/file (discovery/hosting-root) slug)]
    (.mkdirs dir)
    (doseq [[name content] files]
      (if (= "server.jar" name)
        (write-jar! (io/file dir name) content)
        (spit (io/file dir name) content)))
    dir))

(deftest suggest-slug-test
  (is (= "oldworld" (discovery/suggest-slug "oldworld" #{})))
  (is (= "my-world" (discovery/suggest-slug "My World!" #{})))
  (is (= "my-world-3" (discovery/suggest-slug "My World" #{"my-world" "my-world-2"})))
  (is (= "oldworld-2" (discovery/suggest-slug "oldworld" #{"oldworld"})))
  (is (= "server" (discovery/suggest-slug "!!!" #{})))
  (is (= 32 (count (discovery/suggest-slug (apply str (repeat 40 "a")) #{(apply str (repeat 32 "a"))})))))

(deftest scan-test
  (make-installed-server "registered")
  (make-dir! "oldworld" {"server.jar" "1.21.4" "server.properties" "server-port=25599\nwhite-list=true\n"})
  (make-dir! "Bad Name" {"server.properties" "motd=hi\n"})
  (make-dir! "bad-name" {"notes.txt" "not a server, but the name is taken"})
  (make-dir! "My World" {"server.properties" "motd=hi\n"})
  (make-dir! "my world!" {"server.properties" "motd=hi\n"})
  (let [found (discovery/scan)]
    (is (= ["Bad Name" "My World" "my world!" "oldworld"] (map :folder found)))
    (testing "invalid folder names get a unique suggested id"
      (is (= ["bad-name-2" "my-world" "my-world-2" "oldworld"] (map :slug found)))
      (is (= [true true true false] (map :rename? found))))
    (is (= {:folder "oldworld" :slug "oldworld" :rename? false :version "1.21.4" :port 25599
            :whitelist? true :notes []}
           (last found)))))

(deftest import-test
  (with-redefs [sync/lookup-uuid (constantly nil)]
    (let [admin (users/by-username "admin")
          dir (make-dir! "oldworld" {"server.jar" "1.21.4"
                                     "server.properties" "server-port=25599\nwhite-list=true\nmotd=Old\n"
                                     "whitelist.json" (json/write-str [{"uuid" "u-1" "name" "Steve"}])})
          server (discovery/import! "oldworld" admin)]
      (testing "the record matches the folder"
        (is (= ["oldworld" "1.21.4" 25599 "whitelist" 0]
               ((juxt :name :mc_version :exposed_port :list_mode :auto_update) server)))
        (is (= (.getCanonicalPath dir) (.getCanonicalPath (servers/server-dir server)))))
      (testing "the directory is prepared"
        (is (str/includes? (slurp (io/file dir "eula.txt")) "eula=true"))
        (let [p (props/->map (props/read-props dir))]
          (is (= (str (:mc_port server)) (get p "server-port")))
          (is (= "Old" (get p "motd")))))
      (testing "the whitelist is kept"
        (is (= ["Steve"] (map :player_name (pl/entries (:id server) "whitelist"))))
        (is (= ["Steve"] (map #(get % "name") (sync/read-list-file server :whitelist)))))
      (testing "it is no longer listed"
        (is (empty? (discovery/scan))))
      (is (thrown? clojure.lang.ExceptionInfo (discovery/import! "oldworld" admin))))))

(deftest import-rename-test
  (with-redefs [sync/lookup-uuid (constantly nil)]
    (let [admin (users/by-username "admin")]
      (make-dir! "Old World" {"server.properties" "motd=hi\n"})
      (make-dir! "Other One" {"server.properties" "motd=hi\n"})
      (make-dir! "taken" {"notes.txt" "hi"})
      (testing "the suggested id renames the folder"
        (let [server (discovery/import! "Old World" admin)]
          (is (= ["old-world" "Old World"] ((juxt :name :display_name) server)))
          (is (not (.exists (io/file (discovery/hosting-root) "Old World"))))
          (is (.exists (io/file (servers/server-dir server) "server.properties")))))
      (testing "an edited id is checked"
        (doseq [[slug message] [["Bad Id" "lowercase"] ["old-world" "already exists"] ["taken" "folder named taken"]]]
          (let [e (is (thrown? clojure.lang.ExceptionInfo (discovery/import! "Other One" slug admin)))]
            (is (str/includes? (:user-message (ex-data e)) message))))
        (is (.exists (io/file (discovery/hosting-root) "Other One"))))
      (testing "an edited id is used"
        (is (= "other" (:name (discovery/import! "Other One" " other " admin))))
        (is (.isDirectory (io/file (discovery/hosting-root) "other")))))))

(deftest import-web-test
  (make-dir! "oldworld" {"server.properties" "motd=hi\n"})
  (make-dir! "My World" {"server.properties" "motd=hi\n"})
  (let [sid (:sid (session/initialize-session "admin" "ignored"))
        post (fn [form] (bs/app (tu/request :post "/api/global/import" :sid sid :form form)))
        rows {:row_folders ["My World" "oldworld"] :row_slugs ["mine" "oldworld"]}]
    (let [page (tu/body-str (bs/app (tu/request :get "/admin/settings/minecraft" :sid sid)))]
      (is (str/includes? page "oldworld"))
      (is (str/includes? page "value=\"my-world\"")))
    (is (str/includes? (tu/body-str (post (assoc rows :folders "oldworld"))) "accept the Minecraft EULA"))
    (is (nil? (servers/by-slug "oldworld")))
    (let [resp (post (assoc rows :folders ["oldworld" "My World"] :eula "1"))]
      (is (str/includes? (tu/body-str resp) "Imported 2 servers."))
      (is (= "servers-changed" (get-in resp [:headers "HX-Trigger"]))))
    (is (some? (servers/by-slug "oldworld")))
    (is (= "My World" (:display_name (servers/by-slug "mine"))))))
