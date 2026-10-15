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

(ns com.blockshipper.pages.servers.players
  "Players and uptime tabs."
  (:require [clojure.string :as str]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.minecraft.player-import :as player-import]
            [com.blockshipper.minecraft.player-sync :as sync]
            [com.blockshipper.pages.servers.page :as page]
            [com.blockshipper.player-lists :as pl]
            [com.blockshipper.players :as players]
            [com.blockshipper.response :as r]
            [com.blockshipper.ui :as ui]
            [com.blockshipper.uptime :as uptime]
            [com.blockshipper.util :as u]))

(defn players-table
  "OPS overrides the operator names read from ops.json, for a running server that
  may not have saved the file yet."
  [server & [message ops]]
  (let [id (:id server)
        online (set (map str/lower-case (inst/online-players id)))
        ops (or ops (sync/op-names server))
        desired (sync/desired server)
        bans (into {} (map (juxt (comp str/lower-case :player_name) identity)) (:ban desired))
        whitelisted (set (map (comp str/lower-case :player_name) (:whitelist desired)))
        local-bans (set (map (comp str/lower-case :player_name) (pl/entries id "ban")))
        summary (players/summary id)
        api (str "/api/servers/" (:name server) "/players/")
        action (fn [op player label cls & [confirm]]
                 [:button.btn.small {:class cls
                                     :hx-post (str api op)
                                     :hx-vals (ui/json-attr {:player player})
                                     :hx-target "#players-table" :hx-swap "outerHTML"
                                     :hx-confirm confirm}
                  label])]
    [:section#players-table.card
     [:div.card-header
      [:h2 (str "Players (" (count summary) ")")]
      [:button.btn.btn-secondary.small
       {:hx-post (str api "import") :hx-target "#players-table" :hx-swap "outerHTML"
        :hx-disabled-elt "this"
        :title "Create entries for players found in the world's player data"}
       "Import from world"]]
     (when message (ui/alert (:kind message) (:text message)))
     (if (seq summary)
       [:div.table-scroll
        [:table.table
         [:thead [:tr [:th "Player"] [:th "Status"] [:th "Last login"] [:th "Playtime"] [:th]]]
         [:tbody
          (for [{:keys [player_name last_login playtime]} summary
                :let [k (str/lower-case player_name)
                      ban (bans k)]]
            [:tr
             [:td player_name]
             [:td (cond
                    ban [:span.badge.banned (if (local-bans k) "banned" "banned (global)")]
                    (online k) [:span.badge.online "online"]
                    :else [:span.badge "offline"])
              (when (ops k) [:span.badge.op {:style "margin-left: .5rem"} "op"])]
             [:td.muted (u/format-ts last_login)]
             [:td (u/format-duration playtime)]
             [:td.actions
              (if (ops k)
                (action "deop" player_name "De-op" "btn-secondary" (str "Remove operator status from " player_name "?"))
                (action "op" player_name "OP" "btn-secondary" (str "Make " player_name " an operator?")))
              " "
              (when (online k) (action "kick" player_name "Kick" "btn-secondary" (str "Kick " player_name "?")))
              " "
              (when-not (whitelisted k)
                (action "whitelist" player_name "Whitelist" "btn-secondary"))
              " "
              (cond
                (local-bans k) (action "unban" player_name "Unban" "btn-secondary")
                ban nil
                :else (action "ban" player_name "Ban" "btn-danger" (str "Ban " player_name "?")))]])]]]
       [:p.muted "Nobody has joined this server yet. "
        "If the world was played before, use Import from world to list its players."])]))

(defn players-tab
  [_req _user server]
  [:div.tab-body (players-table server)])

(defn api-player-action
  [req user server op]
  (let [player (get-in req [:params :player])
        result (when (and (#{"op" "deop"} op) (pl/valid-player-name? player))
                 (sync/set-op! server player (= "op" op)
                               (some #(when (= (str/lower-case player) (str/lower-case (:player_name %))) (:uuid %))
                                     (players/summary (:id server)))))
        message (cond
                  (not (pl/valid-player-name? player)) {:kind :error :text "Invalid player name."}
                  (= "ban" op) (do (sync/add-player! server "ban" player {:created-by (:username user)})
                                   {:kind :success :text (str player " is banned.")})
                  (= "unban" op) (do (sync/remove-player! server "ban" player)
                                     {:kind :success :text (str player " is unbanned.")})
                  (= "whitelist" op) (let [uuid (some #(when (= (str/lower-case player) (str/lower-case (:player_name %))) (:uuid %))
                                                      (players/summary (:id server)))]
                                       (sync/add-player! server "whitelist" player {:uuid uuid :created-by (:username user)})
                                       (players/clear-rejected-login! (:id server) player)
                                       {:kind :success :text (str player " added to the whitelist.")})
                  (= "kick" op) (if (inst/send-command! (:id server) (str "kick " player))
                                  {:kind :success :text (str player " was kicked.")}
                                  {:kind :error :text "The server is not running."})
                  (= :no-uuid result) {:kind :error :text (str "Could not look up " player "'s UUID. Start the server and try again.")}
                  (= "op" op) {:kind :success :text (str player " is now an operator.")}
                  (= "deop" op) {:kind :success :text (str player " is no longer an operator.")})
        ;; A running server saves ops.json shortly after the command, so show the expected state.
        ops (when (= :sent result)
              ((if (= "op" op) conj disj) (sync/op-names server) (str/lower-case player)))]
    (r/h-respond (players-table server message ops))))

(defn api-import
  "Create player entries from the world's player data."
  [_req _user server]
  (let [{:keys [found imported updated unnamed]} (player-import/import! server)
        message (if (zero? found)
                  {:kind :info :text "No player data was found in the world."}
                  {:kind :success
                   :text (str "Found " found " player" (when (not= 1 found) "s") " in the world: "
                              imported " new, " updated " with earlier playtime added"
                              (when (pos? unnamed)
                                (str ", " unnamed " skipped because their name could not be found"))
                              ".")})]
    (r/h-respond (players-table server message))))

(defn uptime-tab
  [_req _user server]
  (let [entries (uptime/entries (:id server) 200)
        week-ago (- (u/now-ms) (* 7 24 60 60 1000))
        week-total (->> entries
                        (filter #(> (or (:stopped_at %) (u/now-ms)) week-ago))
                        (map #(- (or (:stopped_at %) (u/now-ms)) (max week-ago (:started_at %))))
                        (reduce + 0))]
    [:div.tab-body
     [:section.card
      [:div.card-header
       [:h2 "Uptime log"]
       [:span.muted (str "Online " (u/format-duration week-total) " in the last 7 days")]]
      (if (seq entries)
        [:div.table-scroll
         [:table.table
          [:thead [:tr [:th "Started"] [:th "Stopped"] [:th "Duration"] [:th "Started by"] [:th "Reason stopped"]]]
          [:tbody
           (for [{:keys [started_at stopped_at duration started_by stop_reason]} entries]
             [:tr
              [:td (u/format-ts started_at)]
              [:td (if stopped_at (u/format-ts stopped_at) [:span.badge.online "running"])]
              [:td (u/format-duration duration)]
              [:td started_by]
              [:td.muted stop_reason]])]]]
        [:p.muted "This server has not been started yet."])]]))

(page/register-tab! "players" players-tab)
(page/register-tab! "uptime" uptime-tab)
