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

(ns com.blockshipper
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [compojure.core :as cjr]
            [compojure.route :as route]
            [com.blockshipper.api.session :as session]
            [com.blockshipper.auth :as auth]
            [com.blockshipper.database.db :as db]
            [com.blockshipper.database.sqlite :as sqlite]
            [com.blockshipper.minecraft :as mc]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.middleware :as mw]
            [com.blockshipper.minecraft.mc-proxy :as proxy]
            [com.blockshipper.pages.dashboard :as dashboard]
            [com.blockshipper.pages.layout :as layout]
            [com.blockshipper.pages.login :as lp]
            [com.blockshipper.pages.not-found :as nf]
            [com.blockshipper.pages.onboarding :as onboarding]
            [com.blockshipper.pages.public :as public]
            [com.blockshipper.pages.server-config :as server-config]
            [com.blockshipper.pages.settings :as app-settings]
            [com.blockshipper.pages.servers.create :as create]
            [com.blockshipper.pages.servers.backups :as backups-page]
            [com.blockshipper.pages.servers.console :as console]
            [com.blockshipper.pages.servers.lists :as lists]
            [com.blockshipper.pages.servers.page :as server-page]
            [com.blockshipper.pages.servers.players :as players-page]
            [com.blockshipper.pages.servers.properties :as properties]
            [com.blockshipper.pages.servers.settings :as server-settings]
            [com.blockshipper.minecraft.player-sync :as player-sync]
            [com.blockshipper.players :as players]
            [com.blockshipper.response :as r]
            [com.blockshipper.scheduler :as scheduler]
            [com.blockshipper.uptime :as uptime]
            [com.blockshipper.users :as users]
            [clojure.tools.logging :as log]
            [com.blockshipper.settings :as s]))

(defonce ^:private server (atom nil))

;; True while Block Shipper waits for first-run setup (no settings file or no account).
(defonce ^:private setup-required (atom false))

(defn- stop-all
  [_req user]
  (doseq [id (inst/running-ids)]
    (inst/stop! id (str "stopped by " (:username user) " (shutdown all)")))
  {:status 204 :headers {"HX-Trigger" "servers-changed"} :body ""})

(cjr/defroutes
  routes
  ;; API Bindings
  ; In debug mode, admins can see the raw request
  (cjr/ANY "/api/echo" req
    (when (:debug (s/get-settings))
      (auth/requires-admin (fn [req _user]
                             (r/respond-generic (with-out-str (pprint/pprint (dissoc req :body)))
                                                "text/plain"))
                           req)))
  ; Do-nothing endpoint for htmx hackery
  (cjr/GET "/api/null" []
    (r/respond-generic "" "text/plain"))
  ; Try logging into the application, requires username and password parameters
  (cjr/POST "/api/login" req
    (session/try-login req))
  ; Sidebar server list
  (cjr/GET "/api/servers/sidebar" req
    (auth/requires-user layout/sidebar-response req))
  (cjr/POST "/api/servers" req
    (auth/requires-admin create/api-create req))
  ;; World archives for the create form, sent in pieces under the request body limit.
  (cjr/POST "/api/uploads" req
    (auth/requires-admin create/api-upload-start req))
  (cjr/PUT "/api/uploads/:id" [id :as req]
    (auth/requires-admin (fn [req user] (create/api-upload-chunk req user id)) req))
  (cjr/DELETE "/api/uploads/:id" [id :as req]
    (auth/requires-admin (fn [req user] (create/api-upload-delete req user id)) req))
  (cjr/POST "/api/servers/stop-all" req
    (auth/requires-admin stop-all req))
  (cjr/GET "/api/servers/:slug/log-tail" [slug :as req]
    (auth/requires-server server-page/api-log-tail req slug))
  (cjr/GET "/api/servers/:slug/stats" [slug :as req]
    (auth/requires-server server-page/api-stats req slug))
  (cjr/GET "/api/servers/:slug/controls" [slug :as req]
    (auth/requires-server (fn [req user srv] (server-page/api-control req user srv "controls")) req slug))
  (cjr/POST "/api/servers/:slug/:action{start|stop|restart}" [slug action :as req]
    (auth/requires-server (fn [req user srv] (server-page/api-control req user srv action)) req slug))
  (cjr/POST "/api/servers/:slug/lists/:list-type{whitelist|ban}/:op{add|remove}" [slug list-type op :as req]
    (auth/requires-server (fn [req user srv] (lists/api-change req user srv list-type op)) req slug))
  (cjr/POST "/api/servers/:slug/players/:op{ban|unban|kick|op|deop}" [slug op :as req]
    (auth/requires-server (fn [req user srv] (players-page/api-player-action req user srv op)) req slug))
  (cjr/POST "/api/servers/:slug/properties" [slug :as req]
    (auth/requires-server properties/api-save req slug))
  (cjr/POST "/api/servers/:slug/settings" [slug :as req]
    (auth/requires-server-admin server-settings/api-save req slug))
  (cjr/POST "/api/servers/:slug/install" [slug :as req]
    (auth/requires-server-admin server-settings/api-install req slug))
  (cjr/GET "/api/servers/:slug/backups" [slug :as req]
    (auth/requires-server-admin backups-page/api-section req slug))
  (cjr/POST "/api/servers/:slug/backups" [slug :as req]
    (auth/requires-server-admin backups-page/api-backup-now req slug))
  (cjr/GET "/api/servers/:slug/backups/:file" [slug file :as req]
    (auth/requires-server-admin (fn [req user srv] (backups-page/api-download req user srv file)) req slug))
  (cjr/POST "/api/servers/:slug/delete" [slug :as req]
    (auth/requires-server-admin server-settings/api-delete req slug))
  ;; Application settings
  (cjr/POST "/api/users" req
    (auth/requires-admin app-settings/api-create-user req))
  (cjr/POST "/api/users/:id/:action{role|servers|password|delete}" [id action :as req]
    (auth/requires-admin (fn [req me] (app-settings/api-user-action req me id action)) req))
  (cjr/POST "/api/account/password" req
    (auth/requires-user app-settings/api-change-password req))
  (cjr/POST "/api/global/settings" req
    (auth/requires-admin app-settings/api-save-global req))
  (cjr/POST "/api/global/config" req
    (auth/requires-admin server-config/api-save req))
  (cjr/POST "/api/global/import/scan" req
    (auth/requires-admin app-settings/api-scan req))
  (cjr/POST "/api/global/import" req
    (auth/requires-admin app-settings/api-import req))
  (cjr/POST "/api/global/lists/:list-type{whitelist|ban}/:op{add|remove}" [list-type op :as req]
    (auth/requires-admin (fn [req me] (lists/api-change req me nil list-type op)) req))
  (cjr/GET "/ws/servers/:slug/console" [slug :as req]
    (auth/requires-server console/console-socket req slug))

  ;; Public pages
  (cjr/GET "/" req
    (public/public-page req))
  (cjr/POST "/public/servers/:slug/start" [slug :as req]
    (public/start-server req slug))

  ;; Admin pages
  (cjr/GET "/admin" req
    (auth/requires-user dashboard/dashboard-page req))
  (cjr/GET "/admin/settings" req
    (auth/requires-user (fn [req me] (app-settings/settings-page req me nil)) req))
  (cjr/GET "/admin/settings/:tab" [tab :as req]
    (auth/requires-user (fn [req me] (app-settings/settings-page req me tab)) req))
  (cjr/GET "/admin/servers/new" req
    (auth/requires-admin create/create-page req))
  (cjr/GET "/admin/servers/:slug" [slug :as req]
    (auth/requires-server (fn [req user srv] (server-page/server-page req user srv "dashboard")) req slug))
  (cjr/GET "/admin/servers/:slug/:tab" [slug tab :as req]
    (auth/requires-server (fn [req user srv] (server-page/server-page req user srv tab)) req slug))

  ; Setup is finished once this handler is reached
  (cjr/GET "/setup" []
    {:status 302 :headers {"Location" "/admin"} :body ""})
  ; Login page
  (cjr/GET "/login" req
    (if (session/has-session? req)
      {:status 302 :headers {"Location" "/admin"} :body ""}
      (lp/login-page)))
  ; Logout - clears session and redirects to login
  (cjr/GET "/logout" req
    (session/logout req))
  (route/not-found (nf/not-found-html)))

(defn- on-startup
  "Bring the database and process bookkeeping into a consistent state and start the
  background services."
  []
  (mc/create-directories)
  (db/get-db-spec)
  ;; Anything still marked running belongs to a previous Block Shipper process.
  (uptime/close-dangling!)
  (players/close-all-sessions! nil)
  (player-sync/install-hooks!)
  (proxy/install-hooks!)
  (proxy/sync-listeners!)
  (scheduler/start!))

(defn- setup-routes
  "The only routes served until setup is finished."
  [req]
  (case [(:request-method req) (:uri req)]
    [:get "/setup"] (onboarding/setup-page)
    [:post "/api/setup"] (locking setup-required
                           (if @setup-required
                             (onboarding/api-setup req (fn []
                                                         (on-startup)
                                                         (reset! setup-required false)))
                             {:status 200 :headers {"HX-Redirect" "/login"} :body ""}))
    (onboarding/setup-redirect req)))

(def app
  (let [wrap-res-fun (fn [handler] (wrap-resource handler "public"))]
    (-> (fn [req] (if @setup-required (setup-routes req) (routes req)))
       wrap-keyword-params
       wrap-params
       wrap-res-fun
       wrap-content-type
       wrap-cookies
       mw/wrap-same-origin
       mw/wrap-body-limit
       mw/wrap-security-headers)))

(defonce ^:private shutdown-hook
  (delay
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn []
                                           (log/info "Block Shipper shutting down, stopping servers")
                                           (inst/stop-all! "block shipper shut down"))))))

(defn start-server
  "Start the web service."
  []
  (s/pin-restart-settings!)
  (when (and (not (s/configured?)) (.exists (io/file (sqlite/db-path))))
    ;; An install from before onboarding existed: keep running on the defaults.
    (log/info (str "No settings file, but a database exists; writing the defaults to " (s/config-path)))
    (s/save-settings! (select-keys (s/get-default-settings) s/config-keys)))
  (reset! setup-required (or (not (s/configured?)) (not (users/any-users?))))
  (if @setup-required
    (log/info "Waiting for first-run setup")
    (on-startup))
  @shutdown-hook
  (let [settings (s/get-settings)]
    (log/info (str "Starting on port " (:listen-port settings)))
    (log/info (str "Settings: " settings))
    (reset! server (jetty/run-jetty (fn [req] (app req)) {:port (:listen-port settings) :join? false}))
    (when @setup-required
      (let [msg (str "Open http://localhost:" (:listen-port settings) "/setup to finish setting up Block Shipper.")]
        (log/info msg)
        (println msg)))))

(defn stop-server
  "Stop the web service."
  []
  (when-some [s @server]
    (.stop s)
    (reset! server nil))
  (scheduler/stop!)
  (proxy/stop-all!))
