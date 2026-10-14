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

(ns com.blockshipper.pages.layout
  "The logged-in application shell: header, sidebar with the server list, main content."
  (:require [clojure.string :as str]
            [com.blockshipper.auth :as auth]
            [com.blockshipper.global-settings :as gs]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.response :as r]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.settings :as s]
            [com.blockshipper.ui :as ui]))

(defn server-status
  "Status keyword for SERVER: the process state, or :disabled for disabled servers that are not running."
  [server]
  (let [status (inst/status (:id server))]
    (if (and (= :offline status) (= "disabled" (:host_mode server)))
      :disabled
      status)))

(defn request-host
  "The hostname the request was made to, without the port."
  [req]
  (some-> (get-in req [:headers "host"]) (str/replace #":\d+$" "")))

(defn public-host
  "The host players connect to: the configured public host, else the host of REQ."
  [req]
  (or (not-empty (str/trim (or (gs/public-host) ""))) (request-host req) "localhost"))

(defn server-address
  "The address players use to connect to SERVER. The default port is left out."
  [server req]
  (let [port (:exposed_port server)]
    (if (= 25565 port) (public-host req) (str (public-host req) ":" port))))

(defn main
  "The swappable main content element."
  [& body]
  (into [:main#main.main-content] body))

(defn current-slug
  "The server slug in the browser's current URL, used to highlight the sidebar entry."
  [req]
  (let [url (or (get-in req [:headers "hx-current-url"]) (:uri req) "")]
    (second (re-find #"/admin/servers/([a-z0-9_-]+)" url))))

(defn sidebar-server-list
  "The server entries in the sidebar for USER."
  [user active-slug]
  (into [:div.server-list-items]
        (for [server (servers/visible-servers user)]
          [:a.nav-button (merge (ui/nav-attrs (str "/admin/servers/" (:name server)))
                                {:class (when (= active-slug (:name server)) "active")})
           (ui/status-dot (server-status server))
           [:span.nav-label (servers/display-name server)]])))

(defn sidebar
  [req user]
  [:aside.sidebar
   [:div.nav-section
    [:a.nav-button (ui/nav-attrs "/admin") (ui/home-icon) "Home"]
    (when (auth/admin? user)
      [:a.nav-button (ui/nav-attrs "/admin/servers/new") (ui/add-icon) "Create Server"])]
   [:div.section-divider
    [:div.section-header
     [:div.section-label "Servers"]
     (when (auth/admin? user)
       [:button.shutdown-button {:hx-post "/api/servers/stop-all"
                                 :hx-confirm "Are you sure you want to shut down all servers?"
                                 :hx-swap "none"}
        (ui/power-icon "12")
        "Shutdown All"])]]
   [:div#server-list {:hx-get "/api/servers/sidebar"
                      :hx-trigger "every 10s, servers-changed from:body, htmx:pushedIntoHistory from:body"
                      :hx-swap "innerHTML"}
    (sidebar-server-list user (current-slug req))]])

(defn header
  [user]
  [:header.header
   [:a.app-logo (ui/nav-attrs "/admin") (ui/app-logo-icon) [:span (:app-name (s/get-settings))]]
   [:div.user-section
    [:span.username (:username user)]
    [:a {:href "/" :title "Public dashboard"} (ui/globe-icon)]
    [:a (merge (ui/nav-attrs "/admin/settings") {:title "Settings"}) (ui/settings-icon)]
    [:a {:href "/logout" :title "Log out"} (ui/logout-icon)]]])

(defn app-page
  "Respond with CONTENT (a `main` element) inside the app shell. htmx navigation
  requests only need the main element, so the shell is skipped for them."
  [req user content]
  (if (and (= "true" (get-in req [:headers "hx-request"]))
           (not= "true" (get-in req [:headers "hx-history-restore-request"])))
    (r/h-respond content)
    (r/h-respond
     (ui/std-header
      (ui/link-stylesheet "/css/main.css")
      [:body
       (header user)
       (sidebar req user)
       content
       (ui/link-js-script "/js/main.js")]))))

(defn sidebar-response
  "The sidebar server list fragment, polled by the sidebar."
  [req user]
  (r/h-respond (sidebar-server-list user (current-slug req))))
