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

(ns com.blockshipper.pages.settings
  "Application settings: user management, your account, global minecraft settings and the
  server configuration file."
  (:require [clojure.string :as str]
            [com.blockshipper.auth :as auth]
            [com.blockshipper.global-settings :as gs]
            [com.blockshipper.minecraft.discovery :as discovery]
            [com.blockshipper.pages.layout :as layout]
            [com.blockshipper.pages.server-config :as server-config]
            [com.blockshipper.pages.servers.create :as create]
            [com.blockshipper.pages.servers.lists :as lists]
            [com.blockshipper.response :as r]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.ui :as ui]
            [com.blockshipper.users :as users]
            [com.blockshipper.util :as u]))

(def tab-defs
  [["users" "User management" true]
   ["account" "My account" false]
   ["minecraft" "Minecraft" true]
   ["server" "Server configuration" true]])

;;; Users

(defn- server-checkboxes
  [all-servers selected-ids]
  (if (seq all-servers)
    [:div.checkbox-grid
     (for [server all-servers]
       [:label.checkbox
        [:input {:type "checkbox" :name "server_ids" :value (:id server)
                 :checked (contains? selected-ids (:id server))}]
        [:span (servers/display-name server)]])]
    [:span.muted "No servers yet."]))

(defn- user-row
  [me user all-servers]
  (let [self? (= (:id me) (:id user))
        api (str "/api/users/" (:id user))]
    [:tr {:id (str "user-" (:id user))}
     [:td [:strong (:username user)] (when self? [:span.muted " (you)"])]
     [:td (if self?
            [:span.badge (:role user)]
            (create/select "role" (map (fn [role] [role role]) users/roles) (:role user)
                           {:class "inline" :hx-post (str api "/role") :hx-trigger "change"
                            :hx-target "#users-card" :hx-swap "outerHTML"}))]
     [:td (cond
            (= "admin" (:role user)) [:span.muted "All servers"]
            :else [:form {:hx-post (str api "/servers") :hx-target "#users-card" :hx-swap "outerHTML"
                          :hx-trigger "change"}
                   (server-checkboxes all-servers (:server-ids user))])]
     [:td.actions
      [:button.btn.btn-secondary.small {:hx-post (str api "/password")
                                        :data-prompt (str "New password for " (:username user) " (min 8 characters)")
                                        :data-prompt-type "password" :data-prompt-min-length 8
                                        :hx-target "#users-card" :hx-swap "outerHTML"}
       "Reset password"]
      " "
      (when-not self?
        [:button.btn.btn-danger.small {:hx-post (str api "/delete")
                                       :hx-confirm (str "Delete " (:username user) "?")
                                       :hx-target "#users-card" :hx-swap "outerHTML"}
         "Delete"])]]))

(defn users-card
  [me & [message errors]]
  (let [all-servers (servers/all-servers)]
    [:div#users-card
     [:section.card
      [:div.card-header [:h2 "Users"]]
      (when message (ui/alert (:kind message) (:text message)))
      [:table.table
       [:thead [:tr [:th "User"] [:th "Role"] [:th "Servers"] [:th]]]
       [:tbody (for [user (users/all-users)] (user-row me user all-servers))]]
      [:p.form-hint {:style "margin-top: 1rem"}
       "Admins can create servers, manage users and moderate every server. "
       "Moderators can manage the servers assigned to them."]]
     [:section.card
      [:div.card-header [:h2 "Add user"]]
      (ui/errors-alert errors)
      [:form.form {:hx-post "/api/users" :hx-target "#users-card" :hx-swap "outerHTML"}
       [:div.form-row
        (create/field "Username" [:input.form-input {:name "username" :required true :autocomplete "off"}])
        (create/field "Password" [:input.form-input {:name "password" :type "password" :required true
                                                      :minlength 8 :autocomplete "new-password"}])]
       (create/field "Role" (create/select "role" [["moderator" "Moderator"] ["admin" "Admin"]] "moderator"))
       [:div.form-field
        [:span.form-label "Servers (moderators)"]
        (server-checkboxes all-servers #{})]
       [:div.form-actions [:button.btn.btn-primary {:type "submit"} "Add user"]]]]]))

(defn- server-ids-param
  [params]
  (let [v (:server_ids params)]
    (->> (if (sequential? v) v [v])
         (keep u/parse-int)
         (filter servers/by-id)
         vec)))

(defn api-create-user
  [req me]
  (let [{:keys [username password role] :as params} (:params req)
        username (some-> username str/trim)
        errors (users/validate-new username password role)]
    (if (seq errors)
      (r/h-respond (users-card me nil errors))
      (let [user (users/create! username password role)]
        (when (= "moderator" role)
          (users/set-assignments! (:id user) (server-ids-param params)))
        (r/h-respond (users-card me {:kind :success :text (str "Added " username ".")}))))))

(defn api-user-action
  "Role, server assignment, password and delete actions on another user."
  [req me user-id action]
  (let [user (users/by-id (u/parse-int user-id))
        params (:params req)
        self? (= (:id me) (:id user))
        message
        (cond
          (nil? user) {:kind :error :text "No such user."}
          (= "role" action)
          (cond self? {:kind :error :text "You can't change your own role."}
                (not (some #{(:role params)} users/roles)) {:kind :error :text "Unknown role."}
                (and (users/last-admin? user) (not= "admin" (:role params))) {:kind :error :text "There must be at least one admin."}
                :else (do (users/set-role! (:id user) (:role params))
                          {:kind :success :text (str (:username user) " is now " (:role params) ".")}))
          (= "servers" action)
          (do (users/set-assignments! (:id user) (server-ids-param params))
              {:kind :success :text (str "Updated servers for " (:username user) ".")})
          (= "password" action)
          (let [password (get-in req [:headers "hx-prompt"])]
            (if (< (count (or password "")) 8)
              {:kind :error :text "Passwords must be at least 8 characters."}
              (do (users/set-password! (:id user) password)
                  {:kind :success :text (str "Password changed for " (:username user) ".")})))
          (= "delete" action)
          (cond self? {:kind :error :text "You can't delete yourself."}
                (users/last-admin? user) {:kind :error :text "There must be at least one admin."}
                :else (do (users/delete! (:id user))
                          {:kind :success :text (str "Deleted " (:username user) ".")}))
          :else {:kind :error :text "Unknown action."})]
    (r/h-respond (users-card me message))))

;;; Account

(defn account-card
  [me & [message]]
  [:section#account-card.card
   [:div.card-header [:h2 "Change password"]]
   (when message (ui/alert (:kind message) (:text message)))
   [:form.form {:hx-post "/api/account/password" :hx-target "#account-card" :hx-swap "outerHTML"}
    (create/field "Current password" [:input.form-input {:name "current" :type "password" :required true
                                                          :autocomplete "current-password"}])
    (create/field "New password" [:input.form-input {:name "password" :type "password" :required true
                                                      :minlength 8 :autocomplete "new-password"}])
    (create/field "Confirm new password" [:input.form-input {:name "confirm" :type "password" :required true
                                                              :minlength 8 :autocomplete "new-password"}])
    [:div.form-actions [:button.btn.btn-primary {:type "submit"} "Change password"]]]
   [:p.form-hint {:style "margin-top: 1rem"}
    "Signed in as " [:strong (:username me)] " (" (:role me) ")."]])

(defn api-change-password
  [req me]
  (let [{:keys [current password confirm]} (:params req)
        message (cond
                  (not (users/check-password (:username me) current)) {:kind :error :text "Your current password is wrong."}
                  (< (count (or password "")) 8) {:kind :error :text "Passwords must be at least 8 characters."}
                  (not= password confirm) {:kind :error :text "The new passwords don't match."}
                  :else (do (users/set-password! (:id me) password)
                            {:kind :success :text "Password changed."}))]
    (r/h-respond (account-card me message))))

;;; Minecraft (global)

(defn minecraft-card
  [& [message]]
  (let [settings (gs/all-settings)
        limit (gs/max-running)]
    [:section#minecraft-card.card
     [:div.card-header [:h2 "Global server settings"]]
     (when message (ui/alert (:kind message) (:text message)))
     [:form.form {:hx-post "/api/global/settings" :hx-target "#minecraft-card" :hx-swap "outerHTML"}
      (create/field "Start method"
                    (create/select "start_method"
                                   gs/start-method-options
                                   (get settings "start_method"))
                    "Players always connect through Block Shipper's port for each server, so addresses don't change between modes.")
      [:div.form-field
       [:span.form-label "Maximum running servers"]
       [:div.inline-form
        [:label.checkbox
         [:input {:type "radio" :name "max_running_mode" :value "infinite" :checked (nil? limit)}]
         [:span "Unlimited"]]
        [:label.checkbox
         [:input {:type "radio" :name "max_running_mode" :value "limit" :checked (some? limit)}]
         [:span "At most"]]
        [:input.form-input.inline {:type "number" :name "max_running" :min 1 :value (or limit 2) :style "width: 6rem"}]]
       [:span.form-hint "When the limit is reached, starting another server stops one with nobody online, or shows an error if every running server has players."]]
      (create/field "Public address"
                    [:input.form-input {:name "public_host" :value (get settings "public_host")
                                        :placeholder "play.example.com"}]
                    "Host name shown on the public dashboard. Leave blank to use the address the page was opened with.")
      [:div.form-actions [:button.btn.btn-primary {:type "submit"} "Save"]]]]))

(defn api-save-global
  [req _me]
  (let [{:keys [start_method max_running_mode max_running public_host]} (:params req)
        limit (u/parse-int max_running)
        host (some-> public_host str/trim)]
    (cond
      (not (some #{start_method} gs/start-methods))
      (r/h-respond (minecraft-card {:kind :error :text "Unknown start method."}))
      (and (= "limit" max_running_mode) (not (and limit (pos? limit))))
      (r/h-respond (minecraft-card {:kind :error :text "The limit must be a number above zero."}))
      (and (seq host) (not (gs/valid-public-host? host)))
      (r/h-respond (minecraft-card {:kind :error :text "The public address should be a host name, without a port."}))
      :else
      (do (gs/put! "start_method" start_method)
          (gs/put! "max_running" (if (= "limit" max_running_mode) limit "infinite"))
          (gs/put! "public_host" (or host ""))
          (r/h-respond (minecraft-card {:kind :success :text "Saved."}))))))

(defn import-card
  "Unregistered server folders in the hosting root, with a form to import them."
  [& [message]]
  (let [found (discovery/scan)]
    [:section#import-card.card
     [:div.card-header
      [:h2 "Import existing servers"]
      [:button.btn.btn-secondary.small {:hx-post "/api/global/import/scan" :hx-target "#import-card" :hx-swap "outerHTML"}
       "Rescan"]]
     [:p.muted (str "Server folders in " (.getPath (discovery/hosting-root)) " that Block Shipper doesn't manage yet.")]
     (when message (ui/alert (:kind message) (:text message)))
     (if (empty? found)
       [:p.muted {:style "margin-top: 1rem"} "No unregistered servers found."]
       [:form.form {:hx-post "/api/global/import" :hx-target "#import-card" :hx-swap "outerHTML"
                    :style "max-width: none; margin-top: 1rem"}
        [:table.table
         [:thead [:tr [:th] [:th "Folder"] [:th "Id"] [:th "Version"] [:th "Port"] [:th "Notes"]]]
         [:tbody
          (for [{:keys [folder slug rename? version port notes]} found]
            [:tr
             [:td [:input {:type "checkbox" :name "folders" :value folder :checked true
                           :aria-label (str "Import " folder)}]]
             [:td {:style "white-space: nowrap"} [:code folder]]
             [:td
              ;; Every row submits its folder and id, so the two lists stay aligned.
              [:input {:type "hidden" :name "row_folders" :value folder}]
              [:input {:type "text" :name "row_slugs" :value slug :required true :maxlength 32
                       :pattern "[a-z0-9][a-z0-9_\\-]{0,31}" :aria-label (str "Id for " folder)
                       :title "Lowercase letters, numbers, dashes or underscores (max 32)"
                       :style "min-width: 10rem"}]]
             [:td (or version [:span.muted "unknown"])]
             [:td (or port [:span.muted "new"])]
             [:td
              (when rename?
                [:div.muted "The folder will be renamed to the id. Edit it if you like."])
              (for [n notes] [:div.muted n])]])]]
        [:p.form-hint "Imported servers keep their world, settings, whitelist and bans. Auto-update starts off; "
         "turn it on in the server's settings once you've made a backup."]
        [:label.checkbox
         [:input {:type "checkbox" :name "eula" :value "1" :required true}]
         [:span "I accept the "
          [:a {:href "https://aka.ms/MinecraftEULA" :target "_blank" :rel "noopener"} "Minecraft EULA"]
          " on behalf of these servers."]]
        [:div.form-actions [:button.btn.btn-primary {:type "submit"} "Import selected"]]])]))

(defn api-scan
  [_req _me]
  (r/h-respond (import-card)))

(defn api-import
  [req me]
  (let [{:keys [folders row_folders row_slugs eula]} (:params req)
        as-vec #(if (string? %) [%] (vec %))
        folders (as-vec folders)
        ids (zipmap (as-vec row_folders) (as-vec row_slugs))]
    (cond
      (not= "1" eula) (r/h-respond (import-card {:kind :error :text "You must accept the Minecraft EULA."}))
      (empty? folders) (r/h-respond (import-card {:kind :error :text "Select at least one server."}))
      :else
      (let [errors (into [] (keep (fn [folder]
                                    (try (discovery/import! folder (get ids folder) me) nil
                                         (catch clojure.lang.ExceptionInfo e (:user-message (ex-data e))))))
                         folders)
            imported (- (count folders) (count errors))
            message (if (seq errors)
                      {:kind :error :text (str/join " " (cons (str "Imported " imported " of " (count folders) ".") errors))}
                      {:kind :success :text (str "Imported " imported (if (= 1 imported) " server." " servers."))})]
        (-> (r/h-respond (import-card message))
            (assoc-in [:headers "HX-Trigger"] "servers-changed"))))))

(defn minecraft-tab
  []
  [:div
   (minecraft-card)
   [:div {:style "margin-top: 1.5rem"} (import-card)]
   [:p.muted {:style "margin: 1.5rem 0 0.75rem"}
    "Global lists are inherited by every server: whitelist servers inherit the global whitelist, "
    "ban list servers inherit the global ban list."]
   (lists/list-editor nil "whitelist")
   (lists/list-editor nil "ban")])

;;; Page

(defn settings-page
  [req me tab]
  (let [visible (filter (fn [[_ _ admin-only]] (or (not admin-only) (auth/admin? me))) tab-defs)
        tab (or tab (if (auth/admin? me) "users" "account"))]
    (if-not (some #(= tab (first %)) visible)
      (auth/not-found)
      (layout/app-page
       req me
       (layout/main
        [:h1.dashboard-title "Settings"]
        (ui/tabs tab (for [[k label] visible] [k label (str "/admin/settings/" k)]))
        [:div.tab-body.settings-layout
         (case tab
           "users" (users-card me)
           "account" (account-card me)
           "minecraft" (minecraft-tab)
           "server" (server-config/config-card))])))))
