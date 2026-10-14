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

(ns com.blockshipper.pages.servers.properties
  "server.properties editor."
  (:require [clojure.string :as str]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.minecraft.properties :as props]
            [com.blockshipper.minecraft.setup :as setup]
            [com.blockshipper.pages.servers.page :as page]
            [com.blockshipper.response :as r]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.ui :as ui]))

(def bool [["true" "Yes"] ["false" "No"]])

;; [key label input default]; input is :text, :number or a seq of [value label] options.
(def common-properties
  [["motd" "Message of the day" :text ""]
   ["difficulty" "Difficulty" [["peaceful" "Peaceful"] ["easy" "Easy"] ["normal" "Normal"] ["hard" "Hard"]] "easy"]
   ["gamemode" "Game mode" [["survival" "Survival"] ["creative" "Creative"] ["adventure" "Adventure"] ["spectator" "Spectator"]] "survival"]
   ["max-players" "Max players" :number "20"]
   ["pvp" "PvP" bool "true"]
   ["hardcore" "Hardcore" bool "false"]
   ["online-mode" "Online mode (verify accounts)" bool "true"]
   ["allow-flight" "Allow flight" bool "false"]
   ["view-distance" "View distance" :number "10"]
   ["simulation-distance" "Simulation distance" :number "10"]
   ["spawn-protection" "Spawn protection radius" :number "16"]
   ["level-name" "World folder" :text "world"]
   ["level-seed" "World seed" :text ""]])

(def common-keys (set (map first common-properties)))

(defn- input-name [k] (str "prop:" k))

(defn- property-input
  [k input value]
  (cond
    (= :text input) [:input.form-input {:name (input-name k) :value value}]
    (= :number input) [:input.form-input {:name (input-name k) :value value :type "number"}]
    :else (into [:select.form-input {:name (input-name k)}]
                (for [[v label] input] [:option {:value v :selected (= v value)} label]))))

(defn properties-form
  [server & [message]]
  (let [current (props/->map (props/read-props (servers/server-dir server)))
        others (sort (remove #(or (common-keys %) (setup/managed-keys %)) (keys current)))]
    [:form#properties-form.form {:hx-post (str "/api/servers/" (:name server) "/properties")
                                 :hx-target "this" :hx-swap "outerHTML"}
     (when (inst/running? (:id server))
       (ui/alert :info "The server is running. Changes apply after a restart."))
     (when message (ui/alert (:kind message) (:text message)))
     (for [[k label input default] common-properties]
       [:label.form-field
        [:span.form-label label]
        (property-input k input (get current k default))])
     [:details.advanced
      [:summary (str "All other properties (" (count others) ")")]
      (if (seq others)
        [:div.form
         (for [k others]
           [:label.form-field [:span.form-label k] (property-input k :text (get current k))])]
        [:p.muted "Other properties appear here once the server has started for the first time."])]
     [:fieldset.form-group
      [:legend "Managed by Block Shipper"]
      (for [k (sort setup/managed-keys)]
        [:div.form-field
         [:span.form-label k]
         [:code (get (setup/managed-properties server) k)]])
      [:span.form-hint "Ports and the whitelist are set from the server's Settings tab and the global settings."]]
     [:div.form-actions
      [:button.btn.btn-primary {:type "submit"} "Save properties"]]]))

(defn properties-tab
  [_req _user server]
  [:div.tab-body.settings-layout [:section.card (properties-form server)]])

(defn- valid-key?
  "Property names are restricted to the characters Minecraft uses, so a key can't
  smuggle in a managed property (e.g. with whitespace that ends the key when read)."
  [k]
  (boolean (re-matches #"[A-Za-z0-9._-]{1,64}" k)))

(defn- valid-level-name?
  "The world folder must stay inside the server directory."
  [v]
  (boolean (and (re-matches #"[A-Za-z0-9 ._-]{1,64}" v) (not (str/includes? v "..")))))

(defn submitted-properties
  "The prop:* fields in PARAMS (unknown-looking and managed keys and line breaks removed)."
  [params]
  (into {}
        (for [[k v] params
              :let [k (name k)]
              :when (str/starts-with? k "prop:")
              :let [prop (subs k 5)]
              :when (and (valid-key? prop) (not (setup/managed-keys prop)))]
          [prop (str/replace (str v) #"[\x00-\x1f\x7f]+" " ")])))

(defn api-save
  [req _user server]
  (let [m (submitted-properties (:params req))
        level-name (get m "level-name")]
    (if (and level-name (not (valid-level-name? level-name)))
      (r/h-respond (properties-form server {:kind :error
                                            :text "The world folder must be a plain folder name (letters, numbers, spaces, dots, dashes, underscores)."}))
      (do (props/update-props! (servers/server-dir server) m)
          (r/h-respond (properties-form server {:kind :success :text "Properties saved."}))))))

(page/register-tab! "properties" properties-tab)
