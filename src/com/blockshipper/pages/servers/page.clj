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

(ns com.blockshipper.pages.servers.page
  "The per-server page: header with controls, tab bar and the selected tab."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [com.blockshipper.auth :as auth]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.minecraft.setup :as setup]
            [com.blockshipper.pages.dashboard :as dashboard]
            [com.blockshipper.pages.layout :as layout]
            [com.blockshipper.players :as players]
            [com.blockshipper.response :as r]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.ui :as ui]
            [com.blockshipper.util :as u]))

(defn server-url
  ([server] (str "/admin/servers/" (:name server)))
  ([server tab] (str "/admin/servers/" (:name server) "/" tab)))

(defn installed?
  [server]
  (.exists (io/file (servers/server-dir server) "server.jar")))

;;; Controls

(defn controls
  "Status pill and start/stop/restart buttons. Polls so state changes show up."
  [server & [message]]
  (let [status (layout/server-status server)
        id (:id server)
        api (str "/api/servers/" (:name server))
        button (fn [action label cls & [confirm]]
                 [:button.btn {:class cls
                               :hx-post (str api "/" action)
                               :hx-target "#server-controls"
                               :hx-swap "outerHTML"
                               :hx-confirm confirm}
                  label])]
    [:div#server-controls.server-controls {:hx-get (str api "/controls")
                                           :hx-trigger "every 5s"
                                           :hx-swap "outerHTML"}
     [:span.status-pill {:class (ui/status-class status)}
      (ui/status-dot status) (ui/status-label status)
      (when (= :running status)
        (let [n (count (inst/online-players id))]
          [:span.muted (str " · " n (if (= 1 n) " player" " players"))]))]
     (case status
       (:offline :disabled) (when (and (installed? server) (not= :disabled status))
                              (button "start" "Start" "btn-primary"))
       :running (list (button "restart" "Restart" "btn-secondary" "Restart the server? Players will be disconnected.")
                      (button "stop" "Stop" "btn-danger" "Stop the server? Players will be disconnected."))
       (:starting :stopping) (button "stop" "Force stop" "btn-danger" "Stop the server now?"))
     (when message
       (ui/alert (:kind message) (:text message)))]))

(defn- start-message
  [server started-by]
  (try
    (let [{:keys [status stopping]} (inst/start! server started-by)]
      (when (= :queued status)
        {:kind :info :text (str "Stopping " stopping " to make room first.")}))
    (catch clojure.lang.ExceptionInfo e
      {:kind :error :text (or (:user-message (ex-data e)) (.getMessage e))})
    (catch Exception e
      (log/error e "Start failed")
      {:kind :error :text (str "Start failed: " (.getMessage e))})))

(defn- controls-response
  [server message]
  (-> (r/h-respond (controls server message))
      (assoc-in [:headers "HX-Trigger"] "servers-changed")))

(defn api-control
  "Handle start/stop/restart/controls for SERVER."
  [_req user server action]
  (case action
    "controls" (r/h-respond (controls server))
    "start" (controls-response server (start-message server (:username user)))
    "stop" (do (inst/stop! (:id server) (str "stopped by " (:username user)))
               (controls-response server nil))
    "restart" (let [stopped (inst/stop! (:id server) (str "restarted by " (:username user)))]
                (future
                  (when @stopped
                    (try (inst/start! (servers/by-id (:id server)) (:username user))
                         (catch Exception e (log/error e "Restart failed")))))
                (controls-response server nil))
    (auth/not-found)))

;;; Install status

(defn install-banner
  [server]
  (let [{:keys [state message]} (setup/install-status (:id server))]
    (cond
      (= :installing state)
      [:div {:hx-get (server-url server) :hx-trigger "every 3s" :hx-select "#main" :hx-target "#main" :hx-swap "outerHTML"
             :hx-disinherit "*"}
       (ui/alert :info [:span.spinner] message)]
      (= :failed state)
      (ui/alert :error message " You can retry from the Settings tab.")
      (not (installed? server))
      (ui/alert :error "This server is not installed. Install a version from the Settings tab."))))

;;; Dashboard tab

(defn console-tail
  [server-id n]
  (let [lines (inst/log-lines server-id)]
    (subvec lines (max 0 (- (count lines) n)))))

(defn latest-log-tail
  "The last N lines of logs/latest.log, for when the server is not running."
  [server n]
  (let [f (io/file (servers/server-dir server) "logs" "latest.log")]
    (if (.exists f)
      (with-open [rdr (io/reader f)]
        (let [lines (vec (line-seq rdr))]
          (subvec lines (max 0 (- (count lines) n)))))
      [])))

(defn log-lines-for
  [server n]
  (if (inst/running? (:id server))
    (console-tail (:id server) n)
    (latest-log-tail server n)))

(defn log-view
  [lines & [attrs]]
  [:pre.log-view (merge {:data-autoscroll "true"} attrs)
   (for [line lines] [:div.log-line line])])

(defn dashboard-tab
  [_req server]
  (let [id (:id server)
        week-ago (- (u/now-ms) (* 7 dashboard/day-ms))]
    [:div.tab-body
     [:div.panel-grid
      [:section.card.span-2
       [:div.card-header
        [:h2 "Players online"]
        [:select.form-input.inline {:onchange "bsReloadChart(this)" :data-target "players-chart"
                                    :data-url (str "/api/servers/" (:name server) "/stats")}
         [:option {:value "24h"} "Last 24 hours"]
         [:option {:value "7d"} "Last 7 days"]
         [:option {:value "30d"} "Last 30 days"]]]
       [:div.chart-container.tall
        [:canvas#players-chart {:data-chart (ui/json-attr (dashboard/chart-data id (- (u/now-ms) dashboard/day-ms) 288))}]]]
      [:section.card
       [:div.card-header [:h2 "Recent logins"]]
       (let [logins (players/recent-logins id 12)]
         (if (seq logins)
           [:table.table
            [:tbody
             (for [{:keys [player_name joined_at left_at]} logins]
               [:tr
                [:td player_name]
                [:td.muted (u/format-ts joined_at)]
                [:td (if left_at
                       [:span.muted (u/format-duration (- left_at joined_at))]
                       [:span.badge.online "online"])]])]]
           [:p.muted "Nobody has joined yet."]))]
      [:section.card
       [:div.card-header [:h2 "This week"]]
       (let [summary (players/summary id)
             recent (filter #(>= (or (:last_login %) 0) week-ago) summary)]
         [:div.widget-stats.vertical
          (dashboard/stat "Players this week" (count recent))
          (dashboard/stat "Players all time" (count summary))
          (dashboard/stat "Version" (or (:mc_version server) "—"))
          (dashboard/stat "Pending update" (if (= 1 (:pending_update server)) "Yes" "No"))])]
      [:section.card.span-2
       [:div.card-header
        [:h2 "Server log"]
        [:a.btn.btn-secondary.small (ui/nav-attrs (server-url server "console")) "Open console"]]
       [:div {:hx-get (str "/api/servers/" (:name server) "/log-tail")
              :hx-trigger "every 5s"
              :hx-swap "innerHTML"}
        (log-view (log-lines-for server 50))]]]]))

(defn api-log-tail
  [_req _user server]
  (r/h-respond (log-view (log-lines-for server 50))))

(defn api-stats
  [req _user server]
  (let [range-ms (case (get-in req [:params :range])
                   "7d" (* 7 dashboard/day-ms)
                   "30d" (* 30 dashboard/day-ms)
                   dashboard/day-ms)]
    (r/respond-generic (json/write-str (dashboard/chart-data (:id server) (- (u/now-ms) range-ms) 288))
                       "application/json")))

;;; Page

(def tab-defs
  [["dashboard" "Dashboard" false]
   ["console" "Console" false]
   ["whitelist" "Whitelist" false]
   ["blacklist" "Ban list" false]
   ["properties" "Properties" false]
   ["players" "Players" false]
   ["uptime" "Uptime" false]
   ["settings" "Settings" true]])

(defonce ^{:doc "tab name -> (fn [req user server]) returning hiccup; filled in by the tab namespaces."}
  tab-renderers
  (atom {"dashboard" (fn [req _user server] (dashboard-tab req server))}))

(defn register-tab!
  [tab f]
  (swap! tab-renderers assoc tab f))

(defn server-page
  [req user server tab]
  (let [visible-tabs (filter (fn [[_ _ admin-only]] (or (not admin-only) (auth/admin? user))) tab-defs)]
    (if-let [render (and (some #(= tab (first %)) visible-tabs) (get @tab-renderers tab))]
      (layout/app-page
       req user
       (layout/main
        [:div.server-top
         [:div
          [:h1.dashboard-title.compact (servers/display-name server)]
          [:div.muted.server-subtitle
           (layout/server-address server req)
           (when (:mc_version server) (str " · Minecraft " (:mc_version server)))
           (str " · " (:host_mode server))]]
         (controls server)]
        (install-banner server)
        (ui/tabs tab (for [[k label] visible-tabs] [k label (server-url server k)]))
        (render req user server)))
      (auth/not-found))))
