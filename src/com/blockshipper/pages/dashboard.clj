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
  "The admin home: players online across all visible servers, recent players and the
  servers with the most uptime in the last 24 hours."
  (:require [com.blockshipper.pages.layout :as layout]
            [com.blockshipper.players :as players]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.stats :as stats]
            [com.blockshipper.ui :as ui]
            [com.blockshipper.uptime :as uptime]
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

(def max-series
  "Lines on the combined chart; servers past this fold into \"Other\" so every line keeps a distinct color."
  8)

(defn- bucket-max
  "Per bucket of WIDTH ms starting at FROM, the max players among SAMPLES, nil where there are none."
  [samples from width n]
  (let [by-bucket (reduce (fn [acc {:keys [t players]}]
                            (let [i (quot (- t from) width)]
                              (if (< -1 i n) (update acc i (fnil max 0) players) acc)))
                          {} samples)]
    (mapv by-bucket (range n))))

(defn combined-chart-data
  "Players-online chart data for SERVERS since SINCE, one series per server on a shared time axis
  of at most MAX-POINTS buckets, as used by main.js. Leading buckets with no samples are dropped."
  ([servers since max-points] (combined-chart-data servers since max-points (u/now-ms)))
  ([servers since max-points now]
   (let [width (max 1 (long (Math/ceil (/ (- now since) (double max-points)))))
         n (inc (quot (- now since) width))
         samples (stats/series-by-server (mapv :id servers) since)
         rows (for [server servers]
                {:label (servers/display-name server)
                 :values (bucket-max (get samples (:id server)) since width n)})
         rows (if (> (count rows) max-series)
                (let [[shown others] (split-at (dec max-series) rows)]
                  (concat shown
                          [{:label "Other"
                            :values (apply mapv (fn [& vs] (when (some some? vs) (reduce + (keep identity vs))))
                                           (map :values others))}]))
                rows)
         first-idx (or (first (filter (fn [i] (some #(some? (get (:values %) i)) rows)) (range n))) n)]
     {:labels (mapv #(+ since (* % width)) (range first-idx n))
      :series (mapv #(update % :values subvec first-idx) rows)})))

(defn recent-players-card
  [server-ids since]
  (let [players (players/recent-players server-ids since 5)]
    [:section.card
     [:div.card-header [:h2 "Recent players"]]
     (if (seq players)
       [:table.table
        [:thead [:tr [:th "Player"] [:th "Last connected"] [:th "Servers (24h)"]]]
        [:tbody
         (for [{:keys [player_name last_login servers online]} players]
           [:tr
            [:td player_name
             (when online [:span.badge.online {:style "margin-left: .5rem"} "online"])]
            [:td.muted (u/format-ts last_login)]
            [:td servers]])]]
       [:p.muted "Nobody has joined yet."])]))

(defn top-uptime-card
  [servers since]
  (let [by-id (into {} (map (juxt :id identity)) servers)
        rows (uptime/top-since (mapv :id servers) since 5)]
    [:section.card
     [:div.card-header [:h2 "Most uptime (24h)"]]
     (if (seq rows)
       [:table.table
        [:thead [:tr [:th "Server"] [:th "Status"] [:th "Uptime"]]]
        [:tbody
         (for [{:keys [server_id uptime]} rows
               :let [server (by-id server_id)
                     status (layout/server-status server)]]
           [:tr
            [:td [:a (ui/nav-attrs (str "/admin/servers/" (:name server))) (servers/display-name server)]]
            [:td [:span.status-inline (ui/status-dot status) [:span.muted (ui/status-label status)]]]
            [:td (u/format-duration uptime)]])]]
       [:p.muted "No server has run in the last 24 hours."])]))

(defn dashboard-content
  [req user]
  (let [visible (vec (remove #(= "disabled" (:host_mode %)) (servers/visible-servers user)))
        now (u/now-ms)
        since (- now day-ms)]
    (layout/main
     [:h1.dashboard-title "Server Dashboard"]
     [:div#dashboard-panels {:hx-get "/admin"
                             :hx-select "#dashboard-panels"
                             :hx-disinherit "*"
                             :hx-trigger "every 30s"
                             :hx-swap "outerHTML"}
      (if (seq visible)
        [:div.panel-grid
         [:section.card.span-2
          [:div.card-header [:h2 "Players online (24h)"]]
          [:div.chart-container.tall
           [:canvas {:data-chart (ui/json-attr (combined-chart-data visible since 96 now))}]]]
         (recent-players-card (mapv :id visible) since)
         (top-uptime-card visible since)]
        [:div.empty-state
         [:p "No servers yet."]
         [:p.muted "Use " [:strong "Create Server"] " in the sidebar to set one up."]])])))

(defn dashboard-page
  [req user]
  (layout/app-page req user (dashboard-content req user)))
