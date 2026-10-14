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

(ns com.blockshipper.session-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.blockshipper.api.session :as session]
            [com.blockshipper.database.db :as db]
            [com.blockshipper.test-util :as tu]))

(use-fixtures :each tu/temp-env-fixture)

(deftest password-hashing-test
  (let [hashed (session/hash-password "hunter2")]
    (is (session/verify-password "hunter2" hashed))
    (is (not (session/verify-password "wrong" hashed)))))

(deftest sqlite-session-roundtrip-test
  (testing "Sessions stored in sqlite can be read back, including the user."
    (let [sess (session/initialize-session "admin" "admin")]
      (is (db/valid-session? (:sid sess)))
      (is (= "admin" (:user (db/get-session (:sid sess)))))
      (db/delete-session (:sid sess))
      (is (not (db/valid-session? (:sid sess)))))))

(deftest login-test
  (testing "The test fixture's admin account can log in."
    (let [resp (session/try-login {:form-params {"username" "admin" "password" "admin"}})]
      (is (= 200 (:status resp)))
      (is (re-find #"SameSite=Strict" (get-in resp [:headers "Set-Cookie"])))))
  (testing "Bad passwords and unknown users are rejected."
    (is (= 401 (:status (session/try-login {:form-params {"username" "admin" "password" "nope"}}))))
    (is (= 401 (:status (session/try-login {:form-params {"username" "nobody" "password" "admin"}})))))
  (testing "The cookie expires with the session and is Secure over https."
    (let [cookie #(get-in (session/try-login (merge {:form-params {"username" "admin" "password" "admin"}} %))
                          [:headers "Set-Cookie"])]
      (is (re-find #"Max-Age=\d+" (cookie {})))
      (is (not (re-find #"Secure" (cookie {}))))
      (is (re-find #"Secure" (cookie {:scheme :https})))
      (is (re-find #"Secure" (cookie {:headers {"x-forwarded-proto" "https"}}))))))

(deftest session-expiry-test
  (testing "Sessions older than the session lifetime are no longer valid."
    (let [sid (:sid (session/initialize-session "admin" "admin"))]
      (db/exe-db-stmt ["update sessions set time_created = datetime('now', '-31 days') where sid = ?" sid])
      (is (not (db/valid-session? sid)))
      (is (nil? (db/get-session sid))))))

(deftest requires-session-test
  (testing "Unauthenticated htmx requests are redirected via HX-Redirect."
    (let [resp (session/requires-session (constantly :page) {:headers {"hx-request" "true"}})]
      (is (= "/login" (get-in resp [:headers "HX-Redirect"])))))
  (testing "Unauthenticated page requests get a 302."
    (is (= 302 (:status (session/requires-session (constantly :page) {}))))))
