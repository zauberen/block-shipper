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

(ns com.blockshipper.ui
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [ring.util.response :as ring-response]
            [com.blockshipper.settings :as s]))

(defn static-path [path]
  (if-some [last-modified (some-> (io/resource (str "public" path))
                                  ring-response/resource-data
                                  :last-modified
                                  (.getTime))]
    (str path "?t=" last-modified)
    path))

(defn link-stylesheet
  "Generates a stylesheet link for the given resource."
  [resource-path]
  [:link {:rel "stylesheet" :type "text/css" :href (static-path resource-path)}])

(defn link-js-script
  "Generates a script link for the given resource."
  [resource-path]
  [:script {:src (static-path resource-path)}])

(defn std-header
  "Generates the header to wrap your BODY content in, add SCRIPTS to header."
  [scripts body]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    [:title (:app-name (s/get-settings))]
    [:link {:rel "icon" :type "image/svg+xml" :href (static-path "/img/icon.svg")}]
    [:link {:rel "stylesheet" :type "text/css" :href (static-path "/css/theme.css")}]
    (when (not (nil? scripts)) scripts)
    ;; Vendored in resources/public/js/vendor so the app works without internet access.
    (link-js-script "/js/vendor/htmx-2.0.4.min.js")
    (link-js-script "/js/vendor/htmx-ext-ws-2.0.2.js")
    (link-js-script "/js/vendor/chart-4.4.1.umd.js")]
   body])

;;; Icons

(defn app-logo-icon
  []
  [:img.app-logo-icon {:src (static-path "/img/icon.svg") :alt ""}])


(defn settings-icon
  []
  [:svg.header-icon.settings-icon {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2"
           :d "M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"}]
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2"
           :d "M15 12a3 3 0 11-6 0 3 3 0 016 0z"}]])

(defn logout-icon
  []
  [:svg.header-icon.logout-icon {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2"
           :d "M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"}]])

(defn power-icon
  [size]
  [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size :viewBox "0 0 32 32"}
   [:path {:fill "currentColor"
           :d "M25.542 8.354c-1.47-1.766-2.896-2.617-3.025-2.695a1.99 1.99 0 0 0-2.74.723c-.555.96-.24 2.194.706 2.763h.002l.003.003h.004c.102.062 1.124.73 2.08 1.925c1.003 1.26 1.933 3.017 1.937 5.438a9.122 9.122 0 0 1-2.64 6.438c-1.638 1.653-3.878 2.67-6.37 2.67c-2.49 0-4.73-1.017-6.368-2.67a9.105 9.105 0 0 1-2.64-6.437c.005-2.5.995-4.292 2.035-5.558a9.678 9.678 0 0 1 1.425-1.4c.19-.153.346-.264.445-.33l.104-.07l.013-.007l.004-.002a2.036 2.036 0 0 0 .705-2.763a1.991 1.991 0 0 0-2.74-.724c-.127.078-1.554.93-3.023 2.695c-1.463 1.75-2.975 4.51-2.97 8.157c0 7.263 5.825 13.152 13.01 13.155c7.186-.003 13.01-5.892 13.012-13.155c.004-3.648-1.507-6.407-2.97-8.158zM15.5 17.524a2.015 2.015 0 0 0 2.002-2.024V3.357c0-1.118-.897-2.024-2.002-2.024s-2.002.906-2.002 2.024V15.5c0 1.116.897 2.023 2.002 2.023z"}]])

(defn add-icon
  []
  [:svg.nav-icon {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2"
           :d "M12 6v6m0 0v6m0-6h6m-6 0H6"}]])

(defn home-icon
  []
  [:svg.nav-icon {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2"
           :d "M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6"}]])

(defn globe-icon
  []
  [:svg.header-icon {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2"
           :d "M12 21a9 9 0 100-18 9 9 0 000 18zm0 0c2.5-2.4 3.75-5.4 3.75-9S14.5 5.4 12 3m0 18c-2.5-2.4-3.75-5.4-3.75-9S9.5 5.4 12 3M3.6 9h16.8M3.6 15h16.8"}]])

;;; Building blocks

(defn nav-attrs
  "Attributes for an in-app link: htmx swaps only the main content and pushes the URL,
  so the sidebar (and its polling) stays in place."
  [href]
  {:href href
   :hx-get href
   :hx-target "#main"
   :hx-select "#main"
   :hx-swap "outerHTML"
   :hx-push-url "true"})

(defn status-class
  "CSS class for a server status keyword (or 'disabled')."
  [status]
  (case status
    :running "online"
    (:starting :stopping) "listening"
    :disabled "disabled"
    "offline"))

(defn status-label
  [status]
  (case status
    :running "Online"
    :starting "Starting"
    :stopping "Stopping"
    :disabled "Disabled"
    "Offline"))

(defn status-dot
  [status]
  [:div.server-status {:class (status-class status) :title (status-label status)}])

(defn alert
  "A message box. KIND is :error, :success or :info."
  [kind & body]
  (into [:div.alert {:class (name kind)}] body))

(defn errors-alert
  [errors]
  (when (seq errors)
    (alert :error (into [:ul] (map (fn [e] [:li e]) errors)))))

(defn json-attr
  "Encode DATA for use in a data-* attribute."
  [data]
  (json/write-str data))

(defn tabs
  "A tab bar. TABS is a seq of [key label href]; ACTIVE is the selected key."
  [active tabs]
  (into [:nav.tabs]
        (for [[k label href] tabs]
          [:a.tab (merge (nav-attrs href) {:class (when (= k active) "active")}) label])))

(defn copy-field
  "A read-only value with a copy button."
  [value]
  [:div.copy-field
   [:code.copy-value {:title value} value]
   [:button.copy-button {:type "button" :data-copy value} "Copy"]])
