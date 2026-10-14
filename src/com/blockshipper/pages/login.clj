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

(ns com.blockshipper.pages.login
  (:require [com.blockshipper.ui :as ui]
            [com.blockshipper.response :as r]
            [com.blockshipper.settings :as s]))

(defn login-page
  "Returns the login page as a ring response."
  []
  (r/h-respond
   (ui/std-header
    (ui/link-stylesheet "/css/login.css")
    [:body
     [:div.login-container {:hx-get "/api/null" :hx-trigger "load" :hx-push-url "/login" :hx-swap "none"}
      [:h1.app-name (:app-name (s/get-settings))]
      [:div#error-message.error-message "Username or password incorrect"]
      [:form#login-form {:hx-post "/api/login"
                         :hx-target "#login-form"
                         :hx-swap "outerHTML"
                         :hx-indicator "#login-button"}
       [:div.form-group
        [:label.form-label {:for "username"}
         "Username"]
        [:input#username.form-input {:type "text"
                                     :name "username"
                                     :placeholder "Enter your username"
                                     :required ""
                                     :autocomplete "username"}]]
       [:div.form-group
        [:label.form-label {:for "password"}
         "Password"]
        [:input#password.form-input {:type "password"
                                     :name "password"
                                     :placeholder "Enter your password"
                                     :required ""
                                     :autocomplete "current-password"}]]
       [:div.button-container
        [:a.back-button {:href "/"} "Back"]
        [:button#login-button.login-button {:type "submit"
                                            :hx-disable-elt "this"
                                            :hx-indicator "this"}
         [:span.button-text "Log In"]]]]]
     (ui/link-js-script "/js/login.js")])))

