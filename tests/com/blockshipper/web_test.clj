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

(ns com.blockshipper.web-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockshipper :as bs]
            [com.blockshipper.api.session :as session]
            [com.blockshipper.global-settings :as gs]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.minecraft.mc-update :as mcu]
            [com.blockshipper.minecraft.properties :as props]
            [com.blockshipper.minecraft.world-import-test :as wit]
            [com.blockshipper.minecraft.setup :as setup]
            [com.blockshipper.pages.dashboard :as dash]
            [com.blockshipper.players :as players]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.settings :as s]
            [com.blockshipper.stats :as stats]
            [com.blockshipper.test-util :as tu]
            [com.blockshipper.uploads :as uploads]
            [com.blockshipper.uptime :as uptime]
            [com.blockshipper.users :as users]
            [com.blockshipper.util :as u]))

(use-fixtures :each tu/temp-env-fixture)

(defn login
  [username]
  (:sid (session/initialize-session username "ignored")))

(defn create-server!
  [sid form]
  (with-redefs [mcu/download-server-jar (fn [& _] (tu/fake-server-jar))
                mcu/cached-manifest (fn [] (throw (ex-info "offline" {})))]
    (let [resp (bs/app (tu/request :post "/api/servers" :sid sid :form form))]
      (tu/wait-until #(not= :installing (:state (setup/install-status (:id (servers/by-slug (:name form)))))))
      resp)))

(deftest auth-redirects-test
  (is (= 302 (:status (bs/app (tu/request :get "/admin")))))
  (is (= "/login" (get-in (bs/app (tu/request :get "/admin" :headers {"hx-request" "true"})) [:headers "HX-Redirect"])))
  (is (= 200 (:status (bs/app (tu/request :get "/")))))
  (is (= 200 (:status (bs/app (tu/request :get "/login"))))))

(deftest public-dashboard-login-button-test
  (testing "anonymous visitors see Login"
    (let [body (tu/body-str (bs/app (tu/request :get "/")))]
      (is (str/includes? body ">Login<"))
      (is (not (str/includes? body "Admin portal")))))
  (testing "logged-in users see Admin portal"
    (let [body (tu/body-str (bs/app (tu/request :get "/" :sid (login "admin"))))]
      (is (str/includes? body "Admin portal"))
      (is (not (str/includes? body ">Login<"))))))

(deftest login-page-back-button-test
  (is (str/includes? (tu/body-str (bs/app (tu/request :get "/login"))) "href=\"/\"")))

(deftest create-server-flow-test
  (let [sid (login "admin")]
    (testing "admin dashboard renders"
      (let [resp (bs/app (tu/request :get "/admin" :sid sid))]
        (is (= 200 (:status resp)))
        (is (str/includes? (tu/body-str resp) "No servers yet"))))
    (testing "validation errors are shown"
      (let [resp (create-server! sid {:display_name "Survival" :name "survival" :mc_version "1.21.4"})]
        (is (str/includes? (tu/body-str resp) "EULA"))
        (is (nil? (servers/by-slug "survival")))))
    (testing "creating a server installs it"
      (let [resp (create-server! sid {:display_name "Survival" :name "survival" :mc_version "1.21.4"
                                      :eula "1" :mem_min "16M" :mem_max "64M" :host_mode "published"
                                      :list_mode "whitelist"})
            server (servers/by-slug "survival")]
        (is (= "/admin/servers/survival" (get-in resp [:headers "HX-Redirect"])))
        (is (= "1.21.4" (:mc_version server)))
        (is (= :done (:state (setup/install-status (:id server)))))
        (is (.exists (clojure.java.io/file (servers/server-dir server) "server.jar")))
        (is (str/includes? (slurp (clojure.java.io/file (servers/server-dir server) "server.properties"))
                           "enforce-whitelist=true"))))
    (testing "server pages render"
      (doseq [path ["/admin" "/admin/servers/survival" "/api/servers/sidebar" "/api/servers/survival/controls"
                    "/api/servers/survival/log-tail" "/api/servers/survival/stats"]]
        (is (= 200 (:status (bs/app (tu/request :get path :sid sid)))) path)))
    (testing "public dashboard lists the server with its address"
      (gs/put! "public_host" "play.example.com")
      (let [body (tu/body-str (bs/app (tu/request :get "/")))]
        (is (str/includes? body "Survival"))
        (is (str/includes? body "data-copy=\"play.example.com:25565\""))))))

(deftest dashboard-test
  (let [sid (login "admin")
        a (servers/create! {:name "alpha" :display_name "Alpha" :user_id 1})
        b (servers/create! {:name "beta" :display_name "Beta" :user_id 1})
        now (u/now-ms)]
    (stats/record! (:id a) 2 (- now 60000))
    (stats/record! (:id b) 1 (- now 60000))
    (players/open-session! (:id a) "Steve" nil (- now 120000))
    (uptime/open! (:id b) "admin" (- now 3600000))
    (testing "one chart with a line per server, recent players and top uptime"
      (let [body (tu/body-str (bs/app (tu/request :get "/admin" :sid sid)))]
        (is (str/includes? body "Recent players"))
        (is (str/includes? body "Steve"))
        (is (str/includes? body "Most uptime (24h)"))
        (is (str/includes? body "1h 0m"))
        (is (= 1 (count (re-seq #"data-chart=" body))))))
    (testing "the chart shares a time axis across servers"
      (let [{:keys [labels series]} (dash/combined-chart-data [a b] (- now 900000) 15 now)]
        (is (= ["Alpha" "Beta"] (map :label series)))
        (is (every? #(= (count labels) (count (:values %))) series))
        (is (= [2 1] (map #(last (remove nil? (:values %))) series)))))
    (testing "servers past the color limit fold into Other"
      (let [many (for [i (range 10)] {:id (+ 100 i) :name (str "s" i) :display_name (str "S" i)})]
        (is (= (concat (map #(str "S" %) (range 7)) ["Other"])
               (map :label (:series (dash/combined-chart-data many (- now 900000) 15 now)))))))))

(defn- start-upload
  [sid size]
  (bs/app (tu/request :post "/api/uploads" :sid sid :form {:filename "world.zip" :size size})))

(defmacro ^:private with-chunk-mb
  "Run BODY with the :upload-chunk-mb setting at MB."
  [mb & body]
  `(let [settings# (s/get-settings)]
     (with-redefs [s/get-settings (constantly (assoc settings# :upload-chunk-mb ~mb))]
       ~@body)))

(defn- upload-id
  [sid size]
  (get (json/read-str (tu/body-str (start-upload sid size))) "id"))

(defn- put-piece
  [sid id offset ^bytes piece]
  (bs/app (tu/request :put (str "/api/uploads/" id) :sid sid :query (str "offset=" offset) :bytes piece)))

(defn upload!
  "Send BYTES through the upload endpoints in pieces of CHUNK bytes; returns the upload id."
  [sid ^bytes bytes & {:keys [chunk] :or {chunk (uploads/chunk-bytes)}}]
  (let [id (upload-id sid (alength bytes))]
    (loop [offset 0]
      (when (< offset (alength bytes))
        (let [resp (put-piece sid id offset (java.util.Arrays/copyOfRange bytes (int offset)
                                                                          (int (min (alength bytes) (+ offset chunk)))))]
          (is (= 200 (:status resp)) (tu/body-str resp))
          (recur (parse-long (tu/body-str resp))))))
    id))

(deftest create-server-with-world-test
  (let [sid (login "admin")
        form {:display_name "Imported" :name "imported" :mc_version "1.21.4" :eula "1"}]
    (testing "an upload without a world is rejected and no server is created"
      (let [resp (create-server! sid (assoc form :world_upload (upload! sid (wit/zip-bytes {"a.txt" "x"}))))]
        (is (str/includes? (tu/body-str resp) "No Minecraft world"))
        (is (nil? (servers/by-slug "imported")))))
    (testing "a world uploaded in pieces is installed into the new server"
      (let [archive (wit/zip-bytes {"saves/Old/level.dat" "lvl" "saves/Old/region/r.0.0.mca" (apply str (repeat 3000 "x"))})
            id (upload! sid archive :chunk 1000)
            resp (create-server! sid (assoc form :world_upload id))
            server (servers/by-slug "imported")
            dir (servers/server-dir server)]
        (is (= "/admin/servers/imported" (get-in resp [:headers "HX-Redirect"])))
        (is (= "lvl" (slurp (io/file dir "world" "level.dat"))))
        (is (= "world" (get (props/->map (props/read-props dir)) "level-name")))
        (testing "and the upload is consumed"
          (is (nil? (uploads/info id (:id (users/by-username "admin"))))))))
    (testing "an upload survives a form with errors, so it isn't sent again"
      (let [id (upload! sid (wit/zip-bytes {"level.dat" "again"}))
            resp (create-server! sid (assoc form :name "imported" :world_upload id))]
        (is (str/includes? (tu/body-str resp) "Already uploaded: world.zip"))
        (is (str/includes? (tu/body-str resp) id))
        (create-server! sid (assoc form :name "second" :world_upload id))
        (is (= "again" (slurp (io/file (servers/server-dir (servers/by-slug "second")) "world" "level.dat"))))))
    (testing "an unfinished or unknown upload is reported"
      (let [id (upload-id sid 10)]
        (is (str/includes? (tu/body-str (create-server! sid (assoc form :name "partial" :world_upload id)))
                           "upload didn")))
      (is (str/includes? (tu/body-str (create-server! sid (assoc form :name "partial" :world_upload "nope")))
                         "expired"))
      (is (nil? (servers/by-slug "partial"))))
    (testing "an empty upload field creates a server normally"
      (create-server! sid (assoc form :name "fresh" :world_upload ""))
      (is (some? (servers/by-slug "fresh")))
      (is (not (.exists (io/file (servers/server-dir (servers/by-slug "fresh")) "world")))))))

(deftest chunked-upload-test
  (let [sid (login "admin")]
    (testing "a retried piece gets 409 with the bytes already received"
      (let [id (upload-id sid 6)]
        (is (= "3" (tu/body-str (put-piece sid id 0 (.getBytes "abc")))))
        (let [resp (put-piece sid id 0 (.getBytes "abc"))]
          (is (= 409 (:status resp)))
          (is (= "3" (tu/body-str resp))))
        (is (= "6" (tu/body-str (put-piece sid id 3 (.getBytes "def")))))))
    (testing "more bytes than announced are refused, and the piece can be sent again"
      (let [id (upload-id sid 2)]
        (is (= 400 (:status (put-piece sid id 0 (.getBytes "abc")))))
        (is (= "2" (tu/body-str (put-piece sid id 0 (.getBytes "ab")))))))
    (testing "empty and oversized files are refused up front"
      (is (= 400 (:status (start-upload sid 0))))
      (is (= 400 (:status (start-upload sid (inc uploads/max-upload-bytes))))))
    (testing "the piece size defaults to 1 MB"
      (is (= {"chunkSize" (* 1024 1024)} (dissoc (json/read-str (tu/body-str (start-upload sid 3))) "id"))))
    (testing "pieces over the body limit are refused"
      (let [id (upload-id sid (* 4 1024 1024))]
        (is (= 413 (:status (put-piece sid id 0 (byte-array (inc (* 1024 1024)))))))))
    (testing "the piece size follows the :upload-chunk-mb setting"
      (let [piece (byte-array (* 3 512 1024))
            id (with-chunk-mb 2
                 (let [resp (json/read-str (tu/body-str (start-upload sid (* 4 1024 1024))))
                       id (get resp "id")]
                   (is (= (* 2 1024 1024) (get resp "chunkSize")))
                   (is (= (str (alength piece)) (tu/body-str (put-piece sid id 0 piece))))
                   (is (= 413 (:status (put-piece sid id (alength piece) (byte-array (inc (* 2 1024 1024)))))))
                   (testing "other requests keep the 1 MB limit"
                     (is (= 413 (:status (bs/app (tu/request :post "/api/servers" :sid sid :form {:name "x"}
                                                             :headers {"content-length" (str (* 3 512 1024))}))))))
                   id))]
        (testing "and an upload keeps its piece size when the setting changes"
          (is (= (str (* 2 (alength piece))) (tu/body-str (put-piece sid id (alength piece) piece)))))))
    (testing "an out of range setting falls back to 1 MB"
      (with-chunk-mb 100000
        (is (= (* 1024 1024) (uploads/chunk-bytes)))))
    (testing "uploads belong to the admin who started them"
      (users/create! "other" "password123" "admin")
      (let [id (upload-id sid 3)]
        (is (= 400 (:status (put-piece (login "other") id 0 (.getBytes "abc")))))
        (is (= 204 (:status (bs/app (tu/request :delete (str "/api/uploads/" id) :sid sid)))))
        (is (= 400 (:status (put-piece sid id 0 (.getBytes "abc")))))))
    (testing "moderators can't upload"
      (users/create! "mod" "password123" "moderator")
      (is (= 403 (:status (start-upload (login "mod") 3)))))))

(deftest moderator-access-test
  (let [admin-sid (login "admin")
        _ (create-server! admin-sid {:display_name "A" :name "alpha" :mc_version "1.21.4" :eula "1"})
        _ (create-server! admin-sid {:display_name "B" :name "beta" :mc_version "1.21.4" :eula "1"})
        mod (users/create! "mod" "password1" "moderator")
        _ (users/set-assignments! (:id mod) [(:id (servers/by-slug "alpha"))])
        sid (login "mod")]
    (is (= 200 (:status (bs/app (tu/request :get "/admin/servers/alpha" :sid sid)))))
    (is (= 403 (:status (bs/app (tu/request :get "/admin/servers/beta" :sid sid)))))
    (is (= 403 (:status (bs/app (tu/request :get "/admin/servers/new" :sid sid)))))
    (is (= 404 (:status (bs/app (tu/request :get "/admin/servers/alpha/settings" :sid sid)))))
    (is (not (str/includes? (tu/body-str (bs/app (tu/request :get "/api/servers/sidebar" :sid sid))) "beta")))))

(deftest start-stop-via-api-test
  (let [sid (login "admin")]
    (create-server! sid {:display_name "A" :name "alpha" :mc_version "1.21.4" :eula "1" :mem_min "16M" :mem_max "64M"})
    (let [{id :id} (servers/by-slug "alpha")]
      (try
        (is (= "servers-changed" (get-in (bs/app (tu/request :post "/api/servers/alpha/start" :sid sid))
                                         [:headers "HX-Trigger"])))
        (is (tu/wait-until #(inst/ready? id)))
        (bs/app (tu/request :post "/api/servers/alpha/stop" :sid sid))
        (is (tu/wait-until #(not (inst/running? id)) 20000))
        (finally (inst/stop-all! "cleanup"))))))

(deftest server-tabs-test
  (with-redefs [com.blockshipper.minecraft.player-sync/lookup-uuid (constantly nil)]
    (let [sid (login "admin")]
      (create-server! sid {:display_name "A" :name "alpha" :mc_version "1.21.4" :eula "1" :mem_min "16M" :mem_max "64M"})
      (testing "every tab renders"
        (doseq [tab ["dashboard" "console" "whitelist" "blacklist" "properties" "players" "uptime" "settings"]]
          (let [resp (bs/app (tu/request :get (str "/admin/servers/alpha/" tab) :sid sid))]
            (is (= 200 (:status resp)) tab))))
      (testing "htmx navigation returns only the main element"
        (let [body (tu/body-str (bs/app (tu/request :get "/admin/servers/alpha/uptime" :sid sid
                                                    :headers {"hx-request" "true"})))]
          (is (str/starts-with? body "<main"))))
      (testing "ban list editing"
        (let [body (tu/body-str (bs/app (tu/request :post "/api/servers/alpha/lists/ban/add" :sid sid
                                                    :form {:player "Griefer" :reason "tnt"})))]
          (is (str/includes? body "Griefer added")))
        (is (str/includes? (tu/body-str (bs/app (tu/request :post "/api/servers/alpha/lists/ban/add" :sid sid
                                                            :form {:player "bad name!"})))
                           "Player names")))
      (testing "op and deop from the players tab"
        (with-redefs [com.blockshipper.minecraft.player-sync/lookup-uuid (constantly "u-1")]
          (let [body (tu/body-str (bs/app (tu/request :post "/api/servers/alpha/players/op" :sid sid
                                                      :form {:player "Steve"})))]
            (is (str/includes? body "Steve is now an operator")))
          (is (= #{"steve"} (com.blockshipper.minecraft.player-sync/op-names (servers/by-slug "alpha"))))
          (bs/app (tu/request :post "/api/servers/alpha/players/deop" :sid sid :form {:player "Steve"}))
          (is (empty? (com.blockshipper.minecraft.player-sync/op-names (servers/by-slug "alpha"))))))
      (testing "properties save keeps managed keys"
        (bs/app (tu/request :post "/api/servers/alpha/properties" :sid sid
                            :form {"prop:motd" "Hello there" "prop:server-port" "1" "prop:difficulty" "hard"}))
        (let [text (slurp (clojure.java.io/file (servers/server-dir (servers/by-slug "alpha")) "server.properties"))]
          (is (str/includes? text "motd=Hello there"))
          (is (str/includes? text "difficulty=hard"))
          (is (not (str/includes? text "server-port=1\n")))))
      (testing "property keys can't sneak past the managed keys, and the world stays in the server folder"
        (bs/app (tu/request :post "/api/servers/alpha/properties" :sid sid
                            :form {"prop:server-ip\f" "0.0.0.0" "prop:motd" "Line one\nserver-port=1"}))
        (let [text (slurp (clojure.java.io/file (servers/server-dir (servers/by-slug "alpha")) "server.properties"))]
          (is (str/includes? text "server-ip=127.0.0.1"))
          (is (not (str/includes? text "0.0.0.0")))
          (is (str/includes? text "motd=Line one server-port=1"))
          (is (not (re-find #"(?m)^server-port=1$" text))))
        (is (str/includes? (tu/body-str (bs/app (tu/request :post "/api/servers/alpha/properties" :sid sid
                                                            :form {"prop:level-name" "../other/world"})))
                           "plain folder name"))
        (is (not (str/includes? (slurp (clojure.java.io/file (servers/server-dir (servers/by-slug "alpha"))
                                                             "server.properties"))
                                "other/world"))))
      (testing "settings save validates and persists"
        (let [body (tu/body-str (bs/app (tu/request :post "/api/servers/alpha/settings" :sid sid
                                                    :form {:display_name "Alpha" :host_mode "always-active"
                                                           :list_mode "whitelist" :mem_min "1G" :mem_max "2G"
                                                           :exposed_port "25570" :backup_weekly "1"
                                                           :backup_weekly_day "3" :backup_monthly_day "31"})))]
          (is (str/includes? body "Settings saved"))
          (let [server (servers/by-slug "alpha")]
            (is (= ["Alpha" "always-active" "whitelist" 25570 1 3 31 0]
                   ((juxt :display_name :host_mode :list_mode :exposed_port :backup_weekly
                          :backup_weekly_day :backup_monthly_day :auto_update) server)))))
        (is (str/includes? (tu/body-str (bs/app (tu/request :post "/api/servers/alpha/settings" :sid sid
                                                            :form {:display_name "Alpha" :mem_min "lots"})))
                           "Memory must")))
      (testing "delete requires the id to be typed"
        (is (str/includes? (tu/body-str (bs/app (tu/request :post "/api/servers/alpha/delete" :sid sid
                                                            :headers {"hx-prompt" "nope"})))
                           "id didn"))
        (is (= "/admin" (get-in (bs/app (tu/request :post "/api/servers/alpha/delete" :sid sid
                                                    :headers {"hx-prompt" "alpha"}))
                                [:headers "HX-Redirect"])))
        (is (nil? (servers/by-slug "alpha")))))))

(deftest player-whitelist-test
  (with-redefs [com.blockshipper.minecraft.player-sync/lookup-uuid (constantly nil)
                com.blockshipper.minecraft.player-sync/lookup-name (constantly nil)]
    (let [sid (login "admin")]
      (create-server! sid {:display_name "A" :name "alpha" :mc_version "1.21.4" :eula "1" :mem_min "16M" :mem_max "64M"})
      (let [{id :id :as server} (servers/by-slug "alpha")
            uuid "11111111-1111-4111-8111-111111111111"
            dat (io/file (servers/server-dir server) "world" "playerdata" (str uuid ".dat"))]
        (testing "import from world"
          (io/make-parents dat)
          (spit dat "nbt")
          (spit (io/file (servers/server-dir server) "usercache.json")
                (str "[{\"name\":\"Alex\",\"uuid\":\"" uuid "\"}]"))
          (let [body (tu/body-str (bs/app (tu/request :post "/api/servers/alpha/players/import" :sid sid)))]
            (is (str/includes? body "Found 1 player in the world: 1 new"))
            (is (str/includes? body ">Alex<"))
            (is (str/includes? body "/api/servers/alpha/players/whitelist"))))
        (testing "whitelist from the players tab"
          (let [body (tu/body-str (bs/app (tu/request :post "/api/servers/alpha/players/whitelist" :sid sid
                                                      :form {:player "Alex"})))]
            (is (str/includes? body "Alex added to the whitelist"))
            (is (not (str/includes? body "/api/servers/alpha/players/whitelist"))))
          (is (= uuid (:uuid (com.blockshipper.player-lists/find-entry id "whitelist" "Alex")))))
        (testing "refused logins show on the whitelist tab"
          (players/record-rejected-login! id "Stranger" nil)
          (players/record-rejected-login! id "Visitor" nil)
          (players/record-rejected-login! id "Alex" nil)
          (let [body (tu/body-str (bs/app (tu/request :get "/admin/servers/alpha/whitelist" :sid sid)))]
            (is (str/includes? body "Recent login attempts"))
            (is (str/includes? body "Stranger"))
            (is (not (re-find #"(?s)rejected-logins.*Alex" body)) "players already listed are hidden"))
          (let [body (tu/body-str (bs/app (tu/request :post "/api/servers/alpha/lists/whitelist/add" :sid sid
                                                      :form {:player "Stranger"})))]
            (is (str/includes? body "Stranger added")))
          (bs/app (tu/request :post "/api/servers/alpha/lists/whitelist/dismiss" :sid sid :form {:player "Visitor"}))
          (is (= ["Alex"] (map :player_name (players/rejected-logins id 10))))
          (is (not (str/includes? (tu/body-str (bs/app (tu/request :get "/admin/servers/alpha/whitelist" :sid sid)))
                                  "Recent login attempts"))))))))

(deftest debug-echo-test
  (testing "with debug off the echo endpoint is a 404 and other routes still work"
    (is (= 404 (:status (bs/app (tu/request :get "/api/echo")))))
    (is (= 200 (:status (bs/app (tu/request :get "/"))))))
  (testing "with debug on the echo endpoint is for admins only"
    (users/create! "mod" "password1" "moderator")
    (with-redefs [com.blockshipper.settings/get-settings (constantly (assoc (tu/temp-settings tu/*root*) :debug true))]
      (is (= 302 (:status (bs/app (tu/request :get "/api/echo")))))
      (is (= 403 (:status (bs/app (tu/request :get "/api/echo" :sid (login "mod"))))))
      (is (= 200 (:status (bs/app (tu/request :get "/api/echo" :sid (login "admin")))))))))

(deftest cross-origin-test
  (let [sid (login "admin")
        post (fn [origin & [host]]
               (:status (bs/app (tu/request :post "/api/global/settings" :sid sid
                                            :form {:start_method "manual" :max_running_mode "infinite"}
                                            :headers (cond-> {"origin" origin} host (assoc "host" host))))))]
    (testing "requests from other sites, including other ports on the same host, are refused"
      (is (= 403 (post "https://evil.example.com")))
      (is (= 403 (post "http://mc.example.com:9000")))
      (is (= 403 (post "null"))))
    (testing "same-origin requests work, also behind a proxy that drops the port from Host"
      (is (= 200 (post "http://mc.example.com:8081")))
      (is (= 200 (post "https://mc.example.com" "mc.example.com"))))
    (testing "websocket upgrades are checked too"
      (is (= 403 (:status (bs/app (tu/request :get "/ws/servers/alpha/console" :sid sid
                                              :headers {"origin" "https://evil.example.com"
                                                        "upgrade" "websocket"}))))))))

(deftest hardening-test
  (testing "responses carry security headers"
    (let [headers (:headers (bs/app (tu/request :get "/login")))]
      (is (= "DENY" (get headers "X-Frame-Options")))
      (is (= "nosniff" (get headers "X-Content-Type-Options")))))
  (testing "oversized form bodies are refused before they are read"
    (is (= 413 (:status (bs/app (tu/request :post "/api/login" :form {:username "admin" :password "admin"}
                                            :headers {"content-length" (str (* 2 1024 1024))}))))))
  (testing "the create form has no body limit exemption (worlds are uploaded in pieces)"
    (is (= 413 (:status (bs/app (tu/request :post "/api/servers" :sid (login "admin") :form {:name "x"}
                                            :headers {"content-length" (str (* 2 1024 1024))}))))))
  (testing "uploads need a logged in admin"
    (is (= 302 (:status (bs/app (tu/request :post "/api/uploads" :form {:filename "w.zip" :size 3}))))))
  (testing "logins without a username or password fail cleanly"
    (is (= 401 (:status (bs/app (tu/request :post "/api/login" :form {:username "admin"})))))
    (is (= 401 (:status (bs/app (tu/request :post "/api/login" :form {})))))))

(deftest polling-containers-disinherit-test
  (testing "buttons inside polling containers must not inherit hx-select"
    (gs/put! "start_method" "manual")
    (let [sid (login "admin")]
      (create-server! sid {:display_name "A" :name "alpha" :mc_version "1.21.4" :eula "1"})
      (is (str/includes? (tu/body-str (bs/app (tu/request :get "/"))) "hx-disinherit=\"*\""))
      (is (str/includes? (tu/body-str (bs/app (tu/request :get "/admin" :sid sid))) "hx-disinherit=\"*\"")))))

(deftest static-content-type-test
  (testing "static resources are served with a content type so browsers render the SVG icon"
    (is (= "image/svg+xml" (get-in (bs/app (tu/request :get "/img/icon.svg")) [:headers "Content-Type"])))
    (is (str/includes? (tu/body-str (bs/app (tu/request :get "/"))) "app-logo-icon"))))
