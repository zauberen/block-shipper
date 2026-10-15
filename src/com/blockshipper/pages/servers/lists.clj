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

(ns com.blockshipper.pages.servers.lists
  "Whitelist and ban list editors, for one server or (with a nil server) the global lists."
  (:require [clojure.string :as str]
            [com.blockshipper.minecraft.player-sync :as sync]
            [com.blockshipper.pages.servers.page :as page]
            [com.blockshipper.player-lists :as pl]
            [com.blockshipper.response :as r]
            [com.blockshipper.ui :as ui]
            [com.blockshipper.util :as u]))

(def labels {"whitelist" "Whitelist" "ban" "Ban list"})

(defn- api-base
  [server list-type]
  (if server
    (str "/api/servers/" (:name server) "/lists/" list-type)
    (str "/api/global/lists/" list-type)))

(defn- inherits?
  "Whether SERVER inherits the global LIST-TYPE (see player-sync/desired)."
  [server list-type]
  (if (= "whitelist" (:list_mode server))
    (= "whitelist" list-type)
    (= "ban" list-type)))

(defn list-editor
  "The editor for LIST-TYPE. SERVER is nil for the global list."
  [server list-type & [message]]
  (let [id (:id server)
        entries (if (and server (inherits? server list-type))
                  (pl/effective id list-type)
                  (map #(assoc % :inherited false) (pl/entries id list-type)))
        base (api-base server list-type)
        target (str "#list-" list-type)]
    [:section.card {:id (str "list-" list-type)}
     [:div.card-header
      [:h2 (str (labels list-type) " (" (count entries) ")")]]
     (when server
       (cond
         (and (= "whitelist" list-type) (not= "whitelist" (:list_mode server)))
         (ui/alert :info "This server uses the ban list, so the whitelist is not enforced. "
                   "Change the player list mode in Settings to enforce it.")
         (and (= "ban" list-type) (= "whitelist" (:list_mode server)))
         (ui/alert :info "This server uses a whitelist, so it does not inherit the global ban list. "
                   "Bans added here still apply.")))
     (when message (ui/alert (:kind message) (:text message)))
     [:form.inline-form {:hx-post (str base "/add") :hx-target target :hx-swap "outerHTML"}
      [:input.form-input {:name "player" :placeholder "Player name" :required true
                          :pattern "[A-Za-z0-9_]{1,16}" :autocomplete "off"}]
      (when (= "ban" list-type)
        [:input.form-input {:name "reason" :placeholder "Reason (optional)"}])
      [:button.btn.btn-primary {:type "submit"} (if (= "ban" list-type) "Ban" "Add")]]
     (if (seq entries)
       [:div.table-scroll
        [:table.table {:style "margin-top: 1rem"}
         [:thead [:tr [:th "Player"] (when (= "ban" list-type) [:th "Reason"]) [:th "Added"] [:th]]]
         [:tbody
          (for [e entries]
            [:tr
             [:td (:player_name e) " "
              (when (:inherited e) [:span.badge.inherited "global"])]
             (when (= "ban" list-type) [:td.muted (:reason e)])
             [:td.muted (u/format-ts (:created_at e)) (when (:created_by e) (str " by " (:created_by e)))]
             [:td.actions
              (if (:inherited e)
                [:span.muted "Edit in global settings"]
                [:button.btn.btn-secondary.small
                 {:hx-post (str base "/remove") :hx-vals (ui/json-attr {:player (:player_name e)})
                  :hx-target target :hx-swap "outerHTML"}
                 (if (= "ban" list-type) "Unban" "Remove")])]])]]]
       [:p.muted {:style "margin-top: 1rem"} "No entries."])]))

(defn api-change
  "Add or remove a player. Responds with the refreshed editor."
  [req user server list-type op]
  (let [player (some-> (get-in req [:params :player]) str/trim)
        reason (some-> (get-in req [:params :reason]) str/trim not-empty)
        message (cond
                  (not (some #{list-type} pl/list-types)) {:kind :error :text "Unknown list."}
                  (not (pl/valid-player-name? player)) {:kind :error :text "Player names are 1-16 letters, numbers or underscores."}
                  (= "add" op) (if (sync/add-player! server list-type player {:reason reason :created-by (:username user)})
                                 {:kind :success :text (str player " added.")}
                                 {:kind :info :text (str player " is already on the list.")})
                  (= "remove" op) (do (sync/remove-player! server list-type player)
                                      {:kind :success :text (str player " removed.")}))]
    (r/h-respond (list-editor server list-type message))))

(page/register-tab! "whitelist" (fn [_req _user server] [:div.tab-body (list-editor server "whitelist")]))
(page/register-tab! "blacklist" (fn [_req _user server] [:div.tab-body (list-editor server "ban")]))
