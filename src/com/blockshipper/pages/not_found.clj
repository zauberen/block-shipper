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

(ns com.blockshipper.pages.not-found
  (:require [com.blockshipper.settings :as s]
            [com.blockshipper.response :as r]
            [com.blockshipper.ui :as ui]))

(defn not-found-html
  "Get the 404 page."
  []
  (r/h-respond
   (ui/std-header
    (ui/link-stylesheet "/css/notfound.css")
    [:body
     [:a.app-logo {:href "/"}
      (ui/app-logo-icon)
      [:span (:app-name (s/get-settings))]]
     [:div.floating-shapes [:div.shape] [:div.shape] [:div.shape]]
     [:div.error-container
      [:div.error-code "404"]
      [:h1.error-title "Page Not Found"]
      [:p.error-message
       "The page you're looking for doesn't exist or has been moved. Let's get you back on track."]
      [:div.button-group
       [:a.btn.btn-primary {:href "/"}
        "Go Home"]
       [:a.btn.btn-secondary {:href "javascript:history.back()"}
        "Go Back"]]]])))
