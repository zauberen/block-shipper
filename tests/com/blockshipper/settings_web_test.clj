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

(ns com.blockshipper.settings-web-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockshipper :as bs]
            [com.blockshipper.global-settings :as gs]
            [com.blockshipper.minecraft.player-sync :as sync]
            [com.blockshipper.player-lists :as pl]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.test-util :as tu]
            [com.blockshipper.users :as users]
            [com.blockshipper.web-test :refer [login]]))

(use-fixtures :each tu/temp-env-fixture)

(defn- post [sid uri & {:as opts}]
  (let [resp (bs/app (apply tu/request :post uri :sid sid (mapcat identity opts)))]
    (assoc resp :text (tu/body-str resp))))

(deftest settings-pages-render-test
  (let [admin (login "admin")
        _ (users/create! "mod" "password1" "moderator")
        mod (login "mod")]
    (doseq [path ["/admin/settings" "/admin/settings/users" "/admin/settings/account" "/admin/settings/minecraft"]]
      (is (= 200 (:status (bs/app (tu/request :get path :sid admin)))) path))
    (is (= 200 (:status (bs/app (tu/request :get "/admin/settings" :sid mod)))))
    (is (= 404 (:status (bs/app (tu/request :get "/admin/settings/users" :sid mod)))))
    (is (= 403 (:status (bs/app (tu/request :post "/api/users" :sid mod :form {:username "x"})))))))

(deftest user-management-test
  (let [admin-sid (login "admin")
        me (users/by-username "admin")
        server (servers/create! {:name "alpha" :display_name "Alpha" :user_id (:id me)})]
    (testing "adding a moderator with a server"
      (is (str/includes? (:text (post admin-sid "/api/users" :form {:username "mod" :password "password1"
                                                                    :role "moderator" :server_ids (:id server)}))
                         "Added mod"))
      (is (= #{(:id server)} (:server-ids (users/by-username "mod")))))
    (testing "validation"
      (is (str/includes? (:text (post admin-sid "/api/users" :form {:username "mod" :password "short" :role "moderator"}))
                         "taken")))
    (let [mod (users/by-username "mod")]
      (testing "assignments can be cleared"
        (post admin-sid (str "/api/users/" (:id mod) "/servers") :form {})
        (is (empty? (:server-ids (users/by-username "mod")))))
      (testing "password reset via prompt"
        (post admin-sid (str "/api/users/" (:id mod) "/password") :headers {"hx-prompt" "anotherpass"})
        (is (users/check-password "mod" "anotherpass")))
      (testing "you can't demote or delete yourself"
        (post admin-sid (str "/api/users/" (:id me) "/role") :form {:role "moderator"})
        (is (= "admin" (:role (users/by-username "admin"))))
        (is (str/includes? (:text (post admin-sid (str "/api/users/" (:id me) "/delete"))) "delete yourself")))
      (testing "promotion and deletion"
        (post admin-sid (str "/api/users/" (:id mod) "/role") :form {:role "admin"})
        (is (= "admin" (:role (users/by-username "mod"))))
        (post admin-sid (str "/api/users/" (:id mod) "/delete"))
        (is (nil? (users/by-username "mod")))))))

(deftest account-password-test
  (let [sid (login "admin")]
    (is (str/includes? (:text (post sid "/api/account/password" :form {:current "nope" :password "newpassword" :confirm "newpassword"}))
                       "current password is wrong"))
    (is (str/includes? (:text (post sid "/api/account/password" :form {:current "admin" :password "newpassword" :confirm "different"}))
                       "match"))
    (is (str/includes? (:text (post sid "/api/account/password" :form {:current "admin" :password "newpassword" :confirm "newpassword"}))
                       "Password changed"))
    (is (users/check-password "admin" "newpassword"))))

(deftest global-settings-test
  (with-redefs [sync/lookup-uuid (constantly nil)]
    (let [sid (login "admin")]
      (post sid "/api/global/settings" :form {:start_method "manual" :max_running_mode "limit" :max_running "3"
                                              :public_host "play.example.com"})
      (is (= ["manual" 3 "play.example.com"] [(gs/start-method) (gs/max-running) (gs/public-host)]))
      (post sid "/api/global/settings" :form {:start_method "hold" :max_running_mode "infinite" :max_running "3"})
      (is (nil? (gs/max-running)))
      (is (= "hold" (gs/start-method)))
      (is (str/includes? (:text (post sid "/api/global/settings" :form {:start_method "auto"}))
                         "Unknown start method"))
      (is (str/includes? (:text (post sid "/api/global/settings" :form {:start_method "kick" :public_host "bad host:25565"}))
                         "host name"))
      (testing "global lists"
        (post sid "/api/global/lists/ban/add" :form {:player "Griefer" :reason "x"})
        (is (= ["Griefer"] (map :player_name (pl/entries nil "ban"))))
        (post sid "/api/global/lists/ban/remove" :form {:player "Griefer"})
        (is (empty? (pl/entries nil "ban")))))))
