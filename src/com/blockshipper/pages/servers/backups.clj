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

(ns com.blockshipper.pages.servers.backups
  "Backups section of the server Settings tab."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [com.blockshipper.auth :as auth]
            [com.blockshipper.minecraft.backups :as backups]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.pages.servers.settings :as settings]
            [com.blockshipper.response :as r]
            [com.blockshipper.ui :as ui]
            [com.blockshipper.util :as u]))

;; server-id -> {:state :running|:failed :message s}
(defonce manual-backups (atom {}))

(defn- format-size
  [bytes]
  (cond
    (>= bytes (* 1024 1024 1024)) (format "%.1f GB" (/ bytes 1024.0 1024 1024))
    (>= bytes (* 1024 1024)) (format "%.1f MB" (/ bytes 1024.0 1024))
    :else (format "%.0f KB" (/ bytes 1024.0))))

(defn backups-section
  [server]
  (let [{:keys [state message]} (get @manual-backups (:id server))
        running? (= :running state)
        files (backups/list-backups server)
        base (str "/api/servers/" (:name server) "/backups")]
    [:section#backups-section.card
     (when running? {:hx-get base :hx-trigger "every 3s" :hx-swap "outerHTML"})
     [:div.card-header
      [:h2 "Backups"]
      [:button.btn.btn-secondary.small {:hx-post base :hx-target "#backups-section" :hx-swap "outerHTML"
                                        :disabled running?}
       (if running? "Backing up…" "Back up now")]]
     (cond
       running? (ui/alert :info [:span.spinner] "Backup in progress…")
       (= :failed state) (ui/alert :error message))
     (if (seq files)
       [:table.table
        [:thead [:tr [:th "File"] [:th "Size"] [:th "Created"]]]
        [:tbody
         (for [{:keys [name size modified]} (take 50 files)]
           [:tr
            [:td [:a {:href (str base "/" name) :download name} name]]
            [:td.muted (format-size size)]
            [:td.muted (u/format-ts modified)]])]]
       [:p.muted "No backups yet."])
     [:p.form-hint {:style "margin-top: 1rem"} "Stored in " [:code (.getPath (backups/backup-dir server))]]]))

(defn api-section
  [_req _user server]
  (r/h-respond (backups-section server)))

(defn api-backup-now
  [_req user server]
  (let [id (:id server)]
    (when-not (= :running (:state (get @manual-backups id)))
      (swap! manual-backups assoc id {:state :running})
      (future
        (try
          (if (inst/running? id)
            (backups/backup! server (str "manual-" (:username user)))
            (inst/call-while-busy id #(backups/backup! server (str "manual-" (:username user)))))
          (swap! manual-backups dissoc id)
          (catch Exception e
            (log/error e "Manual backup failed for" (:name server))
            (swap! manual-backups assoc id {:state :failed
                                            :message (or (:user-message (ex-data e))
                                                         (str "Backup failed: " (.getMessage e)))})))))
    (r/h-respond (backups-section server))))

(defn api-download
  [_req _user server file-name]
  (if-let [f (backups/backup-file server file-name)]
    {:status 200
     :headers {"Content-Type" "application/zip"
               "Content-Disposition" (str "attachment; filename=\"" (.getName f) "\"")
               "Content-Length" (str (.length f))}
     :body (io/input-stream f)}
    (auth/not-found)))

(swap! settings/extra-sections assoc :backups backups-section)
