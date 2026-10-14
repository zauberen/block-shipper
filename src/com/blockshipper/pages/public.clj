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

(ns com.blockshipper.pages.public
  "The public dashboard: published and always-active servers, no login required."
  (:require [clojure.tools.logging :as log]
            [com.blockshipper.auth :as auth]
            [com.blockshipper.global-settings :as gs]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.pages.layout :as layout]
            [com.blockshipper.response :as r]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.settings :as s]
            [com.blockshipper.ui :as ui]))

(defn- public-status-label
  [status auto? players]
  (case status
    :running (str "Online — " players (if (= 1 players) " player" " players"))
    :starting "Starting…"
    :stopping "Stopping…"
    (if auto? "Sleeping — join to start" "Offline")))

(defn server-card
  [req server message]
  (let [status (inst/status (:id server))
        auto? (gs/auto-start?)
        players (count (inst/online-players (:id server)))
        start-button? (and (not auto?) (= :offline status))]
    [:div.public-card {:id (str "public-" (:name server))}
     [:div.widget-header
      [:div.widget-title
       (ui/status-dot status)
       [:span.widget-name (servers/display-name server)]]
      [:span.public-status (public-status-label status auto? players)]]
     (ui/copy-field (str (layout/public-host req) ":" (:exposed_port server)))
     (when start-button?
       [:div.public-footer
        [:button.btn.btn-primary {:hx-post (str "/public/servers/" (:name server) "/start")
                                  :hx-target (str "#public-" (:name server))
                                  :hx-swap "outerHTML"}
         "Start server"]])
     (when message
       (ui/alert (:kind message) (:text message)))]))

(defn public-grid
  [req]
  (let [servers (servers/public-servers)]
    [:div#public-grid.public-grid {:hx-get "/"
                                   :hx-select "#public-grid"
                                   :hx-disinherit "*"
                                   :hx-trigger "every 15s"
                                   :hx-swap "outerHTML"}
     (if (seq servers)
       (for [server servers] (server-card req server nil))
       [:p.muted "There are no public servers yet."])]))

(defn public-page
  [req]
  (r/h-respond
   (ui/std-header
    (ui/link-stylesheet "/css/main.css")
    [:body.public-body
     [:main.public-main
      [:div.public-top
       [:a.public-logo {:href "/" :title (:app-name (s/get-settings))} (ui/app-logo-icon)]
       [:h1.dashboard-title "Servers"]
       [:a.btn.btn-secondary.small.public-login {:href "/admin"}
        (if (auth/current-user req) "Admin portal" "Login")]]
      (when (gs/auto-start?)
        [:p.muted.public-hint (if (gs/hold-start?)
                                "Sleeping servers start automatically when you join. Hang on while it starts and you'll be let in."
                                "Sleeping servers start automatically when you join. Give it a minute, then reconnect.")])
      (public-grid req)]
     (ui/link-js-script "/js/main.js")])))

(defn start-server
  "Public start button (manual start mode only)."
  [req slug]
  (let [server (servers/by-slug slug)]
    (if (or (nil? server)
            (not (#{"published" "always-active"} (:host_mode server)))
            (gs/auto-start?))
      {:status 404 :headers {"Content-Type" "text/plain"} :body "Not found."}
      (let [message (try
                      (let [{:keys [status stopping]} (inst/start! server "public")]
                        (case status
                          :queued {:kind :info :text (str "Stopping " stopping " to make room, then starting.")}
                          :already-running nil
                          {:kind :success :text "Starting! This usually takes under a minute."}))
                      (catch clojure.lang.ExceptionInfo e
                        {:kind :error :text (or (:user-message (ex-data e)) "Could not start the server.")})
                      (catch Exception e
                        (log/error e "Public start failed for" slug)
                        {:kind :error :text "Could not start the server."}))]
        (r/h-respond (server-card req server message))))))
