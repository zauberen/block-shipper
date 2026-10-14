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

(ns com.blockshipper.pages.dashboard
  "The admin home: an at-a-glance widget per non-disabled server."
  (:require [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.pages.layout :as layout]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.stats :as stats]
            [com.blockshipper.ui :as ui]
            [com.blockshipper.util :as u]))

(def day-ms (* 24 60 60 1000))

(defn chart-data
  "Players-online chart data for SERVER-ID since SINCE, as used by main.js."
  [server-id since max-points]
  (let [samples (stats/downsample (stats/series server-id since) max-points)]
    {:labels (mapv :t samples)
     :values (mapv :players samples)}))

(defn stat
  [label value]
  [:div.stat-item [:span.stat-label label] [:span.stat-value value]])

(defn server-widget
  [req server]
  (let [id (:id server)
        status (layout/server-status server)
        inst (inst/instance id)
        href (str "/admin/servers/" (:name server))]
    [:a.server-widget (ui/nav-attrs href)
     [:div.widget-header
      [:div.widget-title
       (ui/status-dot status)
       [:span.widget-name (servers/display-name server)]]
      [:span.muted (ui/status-label status)]]
     [:div.widget-stats
      (stat "Players" (if inst (count (:players inst)) "—"))
      (stat "Uptime" (if inst (u/format-duration (- (u/now-ms) (:started-at inst))) "—"))
      (stat "Version" (or (:mc_version server) "—"))
      (stat "Address" (layout/server-address server req))]
     [:div.chart-container
      [:canvas {:data-chart (ui/json-attr (chart-data id (- (u/now-ms) day-ms) 96))
                :data-chart-compact "true"}]]]))

(defn dashboard-content
  [req user]
  (let [visible (remove #(= "disabled" (:host_mode %)) (servers/visible-servers user))]
    (layout/main
     [:h1.dashboard-title "Server Dashboard"]
     [:div#server-grid.server-grid {:hx-get "/admin"
                                    :hx-select "#server-grid"
                                    :hx-disinherit "*"
                                    :hx-trigger "every 30s"
                                    :hx-swap "outerHTML"}
      (if (seq visible)
        (for [server visible] (server-widget req server))
        [:div.empty-state
         [:p "No servers yet."]
         [:p.muted "Use " [:strong "Create Server"] " in the sidebar to set one up."]])])))

(defn dashboard-page
  [req user]
  (layout/app-page req user (dashboard-content req user)))
