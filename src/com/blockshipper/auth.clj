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

(ns com.blockshipper.auth
  "Role checks. Roles and assignments are read from the database on every request
  so permission changes apply immediately to existing sessions."
  (:require [com.blockshipper.api.session :as session]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.users :as users]))

(defn admin?
  [user]
  (= "admin" (:role user)))

(defn can-access-server?
  "Admins can moderate every server, moderators only the ones assigned to them."
  [user server]
  (boolean (and user server
                (or (admin? user)
                    (contains? (:server-ids user) (:id server))))))

(defn current-user
  "The user for the request's session, or nil."
  [req]
  (when-let [sess (session/get-session req)]
    (users/by-username (:user sess))))

(defn forbidden
  []
  {:status 403
   :headers {"Content-Type" "text/plain"}
   :body "You do not have permission to do that."})

(defn not-found
  []
  {:status 404
   :headers {"Content-Type" "text/plain"}
   :body "Not found."})

(defn requires-user
  "Call (HANDLER req user) when the request has a valid session for an enabled user."
  [handler req]
  (session/requires-session
   (fn [req]
     (if-let [user (current-user req)]
       (handler req user)
       (session/login-redirect req)))
   req))

(defn requires-admin
  "Call (HANDLER req user) when the session belongs to an admin."
  [handler req]
  (requires-user (fn [req user]
                   (if (admin? user)
                     (handler req user)
                     (forbidden)))
                 req))

(defn requires-server
  "Call (HANDLER req user server) when the user may moderate the server with SLUG."
  [handler req slug]
  (requires-user (fn [req user]
                   (let [server (servers/by-slug slug)]
                     (cond
                       (nil? server) (not-found)
                       (can-access-server? user server) (handler req user server)
                       :else (forbidden))))
                 req))

(defn requires-server-admin
  "Like requires-server but only for admins."
  [handler req slug]
  (requires-server (fn [req user server]
                     (if (admin? user)
                       (handler req user server)
                       (forbidden)))
                   req slug))
