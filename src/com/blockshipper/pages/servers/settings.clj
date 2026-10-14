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

(ns com.blockshipper.pages.servers.settings
  "Block Shipper (non-minecraft) settings for a server. Admin only."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.minecraft.mc-proxy :as proxy]
            [com.blockshipper.minecraft.player-sync :as sync]
            [com.blockshipper.minecraft.provider :as provider]
            [com.blockshipper.minecraft.setup :as setup]
            [com.blockshipper.pages.servers.create :as create]
            [com.blockshipper.pages.servers.page :as page]
            [com.blockshipper.response :as r]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.settings :as s]
            [com.blockshipper.ui :as ui]
            [com.blockshipper.util :as u]
            [com.blockshipper.wrappers.path-utils :as pu])
  (:import [java.nio.file Files CopyOption]))

(def weekdays [[1 "Monday"] [2 "Tuesday"] [3 "Wednesday"] [4 "Thursday"] [5 "Friday"] [6 "Saturday"] [7 "Sunday"]])

(defn- checkbox
  [name checked label]
  [:label.checkbox
   [:input {:type "checkbox" :name name :value "1" :checked (= 1 checked)}]
   [:span label]])

;; Optional extra sections (e.g. backups) contributed by other namespaces: key -> (fn [server] hiccup)
(defonce extra-sections (atom (sorted-map)))

(defn settings-form
  [server & [errors message]]
  (let [auto-update? (provider/auto-update? (keyword (:server_type server)))]
    [:form#settings-form.card.form {:hx-post (str "/api/servers/" (:name server) "/settings")
                                    :hx-target "this" :hx-swap "outerHTML"}
     [:div.card-header [:h2 "Server settings"]]
     (ui/errors-alert errors)
     (when message (ui/alert (:kind message) (:text message)))
     (create/field "Display name" [:input.form-input {:name "display_name" :required true :value (:display_name server)}])
     (create/field "Hosting" (create/select "host_mode" create/host-mode-options (:host_mode server))
                   "Published servers start when someone joins (or presses Start) and stop after 15 minutes without players.")
     (create/field "Player list" (create/select "list_mode" create/list-mode-options (:list_mode server))
                   "Applies the next time the server starts.")
     [:div.form-row
      (create/field "Memory (min)" [:input.form-input {:name "mem_min" :value (:mem_min server)}])
      (create/field "Memory (max)" [:input.form-input {:name "mem_max" :value (:mem_max server)}])]
     (when-let [error (proxy/listener-error (:id server))]
       (ui/alert :error error))
     (create/field "Public port" [:input.form-input {:name "exposed_port" :type "number" :min 1 :max 65535
                                                     :value (:exposed_port server)}]
                   (str "Minecraft itself listens on 127.0.0.1:" (:mc_port server) " behind the Block Shipper proxy."))
     (if auto-update?
       (checkbox "auto_update" (:auto_update server)
                 "Update automatically to the newest release (applied when nobody is playing)")
       [:p.muted "Automatic updates are only available for vanilla servers."])
     [:fieldset.form-group
      [:legend "Backups"]
      (checkbox "backup_on_update" (:backup_on_update server) "Before updating")
      (checkbox "backup_on_offline" (:backup_on_offline server) "When the server stops")
      [:div.inline-form
       (checkbox "backup_weekly" (:backup_weekly server) "Weekly on")
       (create/select "backup_weekly_day" weekdays (:backup_weekly_day server) {:class "inline"})]
      [:div.inline-form
       (checkbox "backup_monthly" (:backup_monthly server) "Monthly on day")
       (create/select "backup_monthly_day" (map (fn [d] [d d]) (range 1 32)) (:backup_monthly_day server) {:class "inline"})
       [:span.form-hint "Days past the end of a month run on its last day."]]]
     [:div.form-actions [:button.btn.btn-primary {:type "submit"} "Save settings"]]]))

(defn version-section
  [server & [message]]
  (let [running? (inst/running? (:id server))
        installing? (= :installing (:state (setup/install-status (:id server))))
        versions (try (provider/available-versions (keyword (:server_type server))) (catch Exception _ nil))]
    [:section#version-section.card
     [:div.card-header [:h2 "Minecraft version"]]
     (when message (ui/alert (:kind message) (:text message)))
     [:p "Installed: " [:strong (or (:mc_version server) "nothing yet")]
      (when (= 1 (:pending_update server)) [:span.badge.inherited {:style "margin-left: .5rem"} "update pending"])]
     [:form.inline-form {:hx-post (str "/api/servers/" (:name server) "/install")
                         :hx-target "#version-section" :hx-swap "outerHTML"
                         :hx-confirm "Install this version? Downgrading can corrupt worlds; make a backup first."
                         :style "margin-top: 1rem"}
      (if versions
        (create/select "version" (map (fn [v] [v v]) versions) (first versions))
        [:input.form-input {:name "version" :placeholder "e.g. 1.21.4" :required true}])
      [:button.btn.btn-secondary {:type "submit" :disabled (or running? installing?)}
       (if installing? "Installing…" "Install")]]
     (when running? [:p.form-hint "Stop the server to change its version."])]))

(defn danger-section
  [server]
  [:section.card
   [:div.card-header [:h2 "Delete server"]]
   [:p.muted "Removes the server from Block Shipper. Its folder is moved to the backups directory, not deleted."]
   [:div.form-actions
    [:button.btn.btn-danger {:hx-post (str "/api/servers/" (:name server) "/delete")
                             :data-prompt (str "Type " (:name server) " to delete this server")
                             :data-prompt-match (:name server)
                             :hx-target "#delete-result"}
     "Delete server"]]
   [:div#delete-result]])

(defn settings-tab
  [_req _user server]
  (into [:div.tab-body.settings-layout
         (settings-form server)
         (version-section server)]
        (concat (for [[_ f] @extra-sections] (f server))
                [(danger-section server)])))

(defn- parse-settings
  [params]
  (let [port (u/parse-int (:exposed_port params))]
    (cond-> {:display_name (some-> (:display_name params) str/trim)
             :host_mode (:host_mode params)
             :list_mode (:list_mode params)
             :mem_min (some-> (:mem_min params) str/trim not-empty)
             :mem_max (some-> (:mem_max params) str/trim not-empty)
             :auto_update (u/checkbox->int (:auto_update params))
             :backup_on_update (u/checkbox->int (:backup_on_update params))
             :backup_on_offline (u/checkbox->int (:backup_on_offline params))
             :backup_weekly (u/checkbox->int (:backup_weekly params))
             :backup_weekly_day (max 1 (min 7 (u/parse-int (:backup_weekly_day params) 7)))
             :backup_monthly (u/checkbox->int (:backup_monthly params))
             :backup_monthly_day (max 1 (min 31 (u/parse-int (:backup_monthly_day params) 1)))}
      port (assoc :exposed_port port))))

(defn api-save
  [req _user server]
  (let [attrs (parse-settings (:params req))
        errors (cond-> (servers/validate attrs (:id server))
                 (and (:mem_min attrs) (not (re-matches #"^\d+[KMGkmg]?$" (:mem_min attrs)))) (conj "Memory must look like 512M or 4G.")
                 (and (:mem_max attrs) (not (re-matches #"^\d+[KMGkmg]?$" (:mem_max attrs)))) (conj "Memory must look like 512M or 4G."))]
    (if (seq errors)
      (r/h-respond (settings-form (merge server attrs) errors))
      (let [updated (servers/update! (:id server) attrs)]
        (when-not (inst/running? (:id server))
          (setup/apply-managed-properties! updated))
        (when (not= (:list_mode server) (:list_mode updated))
          (sync/reconcile! updated))
        (servers/notify-changed! (:id server))
        (-> (r/h-respond (settings-form updated nil {:kind :success :text "Settings saved."}))
            (assoc-in [:headers "HX-Trigger"] "servers-changed"))))))

(defn api-install
  [req _user server]
  (let [version (some-> (get-in req [:params :version]) str/trim)]
    (cond
      (str/blank? version)
      (r/h-respond (version-section server {:kind :error :text "Choose a version."}))
      (not (re-matches create/version-re version))
      (r/h-respond (version-section server {:kind :error :text "That doesn't look like a version."}))
      (inst/running? (:id server))
      (r/h-respond (version-section server {:kind :error :text "Stop the server first."}))
      :else
      (do (setup/install-async! server version)
          {:status 200 :headers {"HX-Redirect" (page/server-url server "settings")} :body ""}))))

(defn api-delete
  [req user server]
  (let [answer (get-in req [:headers "hx-prompt"])]
    (cond
      (not= answer (:name server))
      (r/h-respond (ui/alert :error "The id didn't match, nothing was deleted."))
      (inst/running? (:id server))
      (r/h-respond (ui/alert :error "Stop the server before deleting it."))
      :else
      (let [dir (servers/server-dir server)
            target (io/file (pu/expand-home-path (:backup-root (s/get-settings))) "deleted"
                            (str (:name server) "-" (u/now-ms)))]
        (when (.exists dir)
          (.mkdirs (.getParentFile target))
          (Files/move (.toPath dir) (.toPath target) (into-array CopyOption [])))
        (servers/delete! (:id server))
        (servers/notify-changed! (:id server))
        (log/info (:username user) "deleted server" (:name server) "; files moved to" (.getPath target))
        {:status 200 :headers {"HX-Redirect" "/admin"} :body ""}))))

(page/register-tab! "settings" settings-tab)
