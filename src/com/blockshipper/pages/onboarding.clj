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

(ns com.blockshipper.pages.onboarding
  "First-run setup. Until there is a settings file and an account, every page redirects
  here: the first admin is created, the server configuration is written to
  ~/.blockshipper and the global minecraft settings are stored."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.blockshipper.api.session :as session]
            [com.blockshipper.database.db :as db]
            [com.blockshipper.database.migration :as m]
            [com.blockshipper.global-settings :as gs]
            [com.blockshipper.pages.server-config :as sc]
            [com.blockshipper.pages.servers.create :as create]
            [com.blockshipper.response :as r]
            [com.blockshipper.settings :as s]
            [com.blockshipper.ui :as ui]
            [com.blockshipper.users :as users]))

(defn setup-redirect
  "Send any request made before setup is finished to the setup page."
  [req]
  (if (session/htmx-request? req)
    {:status 200 :headers {"HX-Redirect" "/setup"} :body ""}
    {:status 302 :headers {"Location" "/setup"} :body ""}))

(defn setup-form
  [values errors]
  [:form#setup-form.form {:hx-post "/api/setup" :hx-target "this" :hx-swap "outerHTML"
                          :hx-disabled-elt "find button[type=submit]"}
   (ui/errors-alert errors)
   [:fieldset.form-group
    [:legend "Admin account"]
    (create/field "Username"
                  [:input.form-input {:name "username" :required true :maxlength 32 :autocomplete "username"
                                      :value (or (:username values) "admin")}]
                  "Letters, numbers, dots, dashes or underscores.")
    [:div.form-row
     (create/field "Password" [:input.form-input {:name "password" :type "password" :required true
                                                   :minlength 8 :autocomplete "new-password"}])
     (create/field "Confirm password" [:input.form-input {:name "confirm" :type "password" :required true
                                                           :minlength 8 :autocomplete "new-password"}])]]
   [:fieldset.form-group
    [:legend "Players"]
    (create/field "Start method"
                  (create/select "start_method"
                                 gs/start-method-options
                                 (or (:start_method values) "kick")))
    (create/field "Public address"
                  [:input.form-input {:name "public_host" :value (:public_host values)
                                      :placeholder "play.example.com"}]
                  "Host name shown on the public dashboard. Leave blank to use the address the page was opened with.")]
   (sc/general-fields values)
   (sc/storage-fields values)
   (sc/minecraft-fields values)
   [:details.advanced
    [:summary "Advanced"]
    (sc/advanced-fields values)]
   [:p.form-hint "Everything here except the admin account can be changed later under "
    "Settings → Server configuration and Settings → Minecraft."]
   [:div.form-actions [:button.btn.btn-primary {:type "submit"} "Finish setup"]]])

(defn- page
  [& body]
  (r/h-respond
   (ui/std-header
    (list (ui/link-stylesheet "/css/main.css")
          (ui/link-stylesheet "/css/setup.css"))
    [:body.setup-page
     [:div.setup-container
      [:div.setup-brand (ui/app-logo-icon) [:span (:app-name (s/get-settings))]]
      (into [:section.card] body)]])))

(defn setup-page
  []
  (page
   [:h1.dashboard-title "Welcome"]
   [:p.muted "No configuration was found, so let's set Block Shipper up. Create the first admin account "
    "and check the server settings below; they'll be saved to " [:code (s/config-path)] "."]
   [:div {:style "margin-top: 1.5rem"}
    (setup-form (s/file-settings) nil)]))

(defn- validate
  "Errors in PARAMS that can be found without a database."
  [params config-errors]
  (let [{:keys [username password confirm start_method public_host]} params
        host (some-> public_host str/trim)]
    (cond-> []
      (not (re-matches #"[A-Za-z0-9_.-]{1,32}" (str/trim (or username ""))))
      (conj "Usernames are 1-32 letters, numbers, dots, dashes or underscores.")
      (< (count (or password "")) 8)
      (conj "Passwords must be at least 8 characters.")
      (not= password confirm)
      (conj "The passwords don't match.")
      (not (some #{start_method} gs/start-methods))
      (conj "Unknown start method.")
      (and (seq host) (not (gs/valid-public-host? host)))
      (conj "The public address should be a host name, without a port.")
      :always (into config-errors))))

(defn- redisplay
  [params errors]
  (r/h-respond (setup-form (merge (sc/form-values params) (select-keys params [:username :start_method :public_host]))
                           errors)))

(defn- finished-response
  "Log the new admin in and go to the dashboard. When the web UI port was changed, say
  where it will be after a restart instead of redirecting straight away."
  [req username new-port old-port]
  (let [sid (try (:sid (session/initialize-session username "admin"))
                 (catch Exception e
                   (log/warn "Could not create a session after setup:" (.getMessage e))
                   nil))
        cookie (when sid (session/session-cookie req sid))
        target (if sid "/admin" "/login")]
    (if (= new-port old-port)
      {:status 200 :headers (cond-> {"HX-Redirect" target} cookie (assoc "Set-Cookie" cookie)) :body ""}
      (-> (r/h-respond
           [:div#setup-form
            (ui/alert :success "Setup complete.")
            [:p (str "Block Shipper keeps listening on port " old-port " until it is restarted; after that "
                     "the web interface moves to port " new-port ".")]
            [:div.form-actions [:a.btn.btn-primary {:href target} "Continue"]]])
          (cond-> cookie (assoc-in [:headers "Set-Cookie"] cookie))))))

(defn api-setup
  "Create the first admin and write the configuration, then call ON-COMPLETE to start
  the services that wait for setup."
  [req on-complete]
  (let [params (:params req)
        [config config-errors] (sc/parse params)
        errors (validate params config-errors)
        username (some-> (:username params) str/trim)]
    (if (seq errors)
      (redisplay params errors)
      (let [running (s/get-settings)
            restore! (fn []
                       (s/pin-restart-settings! running)
                       (reset! m/migration-chk-ran nil))]
        ;; Use the chosen database right away. The web server and log file are already
        ;; open, so those two keep their current values until a restart.
        (s/pin-restart-settings! (merge config (select-keys running [:listen-port :logs-root])))
        (reset! m/migration-chk-ran nil)
        (try
          (db/get-db-spec)
          (let [user-errors (into (users/validate-new username (:password params) "admin")
                                  (sc/create-folders! config))]
            (if (seq user-errors)
              (do (restore!)
                  (redisplay params user-errors))
              (do
                ;; Settings first: if anything after this fails, the next start finds no
                ;; account and comes back to this page with the settings filled in.
                (s/save-settings! config)
                (users/create! username (:password params) "admin")
                (gs/put! "start_method" (:start_method params))
                (gs/put! "public_host" (or (some-> (:public_host params) str/trim) ""))
                (log/info (str "Setup complete, created admin " username))
                (on-complete)
                (finished-response req username (:listen-port config) (:listen-port running)))))
          (catch Exception e
            (log/error e "Setup failed")
            (restore!)
            (redisplay params [(str "Setup failed: " (.getMessage e))])))))))
