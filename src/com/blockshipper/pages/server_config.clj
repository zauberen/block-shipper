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

(ns com.blockshipper.pages.server-config
  "The settings file (~/.blockshipper) as a form. Onboarding uses the fields to write the
  first configuration; Settings -> Server configuration edits it afterwards."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [com.blockshipper.pages.servers.create :as create]
            [com.blockshipper.response :as r]
            [com.blockshipper.settings :as s]
            [com.blockshipper.ui :as ui]
            [com.blockshipper.util :as u]
            [com.blockshipper.wrappers.path-utils :as pu]))

(def labels
  {:app-name "Application name"
   :listen-port "Web UI port"
   :debug "Debug mode"
   :minecraft-root-path "Minecraft folder"
   :hosting-root "Servers folder"
   :backup-root "Backups folder"
   :logs-root "Logs folder"
   :sqlite-db-path "Database file"
   :mc-mem-min "Default memory (min)"
   :mc-mem-max "Default memory (max)"
   :java-cmd "Java command"
   :proxy-bind-address "Proxy bind address"
   :nosql-type "Session store"
   :nosql-host "Session store host"
   :nosql-port "Session store port"})

(def session-stores
  [["yes-sql" "Database — sessions are kept in SQLite"]
   ["valkey" "Valkey"]
   ["redis" "Redis"]])

(def ^:private path-keys [:minecraft-root-path :hosting-root :backup-root :logs-root :sqlite-db-path])

;;; Parsing

(defn- valid-port?
  [n]
  (and n (< 0 n 65536)))

(defn- path?
  "Absolute paths, or paths in the home directory (~/...)."
  [p]
  (and (not (str/blank? p))
       (or (str/starts-with? p "~") (.isAbsolute (io/file p)))))

(defn parse
  "Read the configuration fields from form PARAMS. Returns [settings errors]."
  [params]
  (let [text #(some-> (get params %) str str/trim)
        settings (merge (into {} (for [k (concat [:app-name :mc-mem-min :mc-mem-max :java-cmd
                                                  :proxy-bind-address :nosql-host]
                                                 path-keys)]
                                   [k (text k)]))
                        {:listen-port (u/parse-int (text :listen-port))
                         :nosql-port (u/parse-int (text :nosql-port))
                         :debug (= "1" (get params :debug))
                         :nosql-type (some-> (text :nosql-type) not-empty symbol)})
        errors (cond-> []
                 (str/blank? (:app-name settings))
                 (conj "Give the application a name.")
                 (not (valid-port? (:listen-port settings)))
                 (conj "The web UI port must be between 1 and 65535.")
                 :always
                 (into (for [k path-keys :when (not (path? (get settings k)))]
                         (str (labels k) " must be an absolute path, or start with ~.")))
                 (not (every? #(re-matches #"\d+[KkMmGg]?" (or (get settings %) "")) [:mc-mem-min :mc-mem-max]))
                 (conj "Memory sizes look like 512M or 4G.")
                 (str/blank? (:java-cmd settings))
                 (conj "Enter the Java command used to launch servers, e.g. java.")
                 (not (re-matches #"[A-Za-z0-9.:%\[\]-]+" (or (:proxy-bind-address settings) "")))
                 (conj "The proxy bind address should be an IP address or host name, e.g. 0.0.0.0.")
                 (not (some #{(str (:nosql-type settings))} (map first session-stores)))
                 (conj "Unknown session store.")
                 (str/blank? (:nosql-host settings))
                 (conj "Enter the session store host.")
                 (not (valid-port? (:nosql-port settings)))
                 (conj "The session store port must be between 1 and 65535."))]
    [settings errors]))

(defn form-values
  "Values to redisplay after a failed submit: what was typed, not what parsed."
  [params]
  (merge (into {} (for [k s/config-keys :when (contains? params k)] [k (get params k)]))
         {:debug (= "1" (get params :debug))}))

(defn create-folders!
  "Create the folders in SETTINGS. Returns an error message for each one that can't be."
  [settings]
  (vec (for [k [:minecraft-root-path :hosting-root :backup-root :logs-root]
             :let [error (try (pu/create-dir (get settings k)) nil
                              (catch Exception e (str "Couldn't create " (get settings k) ": " (.getMessage e))))]
             :when error]
         error)))

;;; Fields

(defn- restart-hint
  "Hint text for K, noting restart-only keys and whether one is waiting on a restart."
  [k hint running values]
  (let [pending? (and running (contains? s/restart-keys k)
                      (not= (str (get running k)) (str (get values k))))]
    [:span.form-hint
     hint
     (when (contains? s/restart-keys k)
       (list (when hint " ") "Applies after a restart."))
     (when pending?
       (list " " [:span.badge.inherited (str "Restart pending — running with " (get running k))]))]))

(defn- input
  [values running k hint & [attrs]]
  [:label.form-field
   [:span.form-label (labels k)]
   [:input.form-input (merge {:name (name k) :value (str (get values k)) :required true} attrs)]
   (restart-hint k hint running values)])

(defn general-fields
  "VALUES are the settings to show; RUNNING (optional) the settings in effect, to flag
  values that are saved but waiting on a restart."
  [values & [running]]
  [:fieldset.form-group
   [:legend "General"]
   [:div.form-row
    (input values running :app-name "Shown in the header and the browser tab.")
    (input values running :listen-port "Port of this web interface." {:type "number" :min 1 :max 65535})]])

(defn storage-fields
  [values & [running]]
  [:fieldset.form-group
   [:legend "Storage"]
   (input values running :minecraft-root-path "Downloaded server jars are cached here.")
   (input values running :hosting-root "New servers are created here. Existing servers keep their folder.")
   (input values running :backup-root nil)
   (input values running :logs-root nil)
   (input values running :sqlite-db-path "Users, servers and statistics are stored here.")])

(defn minecraft-fields
  [values & [running]]
  [:fieldset.form-group
   [:legend "Minecraft"]
   [:div.form-row
    (input values running :mc-mem-min "Used for new servers." {:placeholder "512M"})
    (input values running :mc-mem-max "Used for new servers." {:placeholder "3G"})]
   (input values running :java-cmd "Java executable used to launch servers, e.g. a full path to a specific JDK.")
   (input values running :proxy-bind-address
          "Address the player-facing ports listen on. 0.0.0.0 accepts connections on every interface.")])

(defn advanced-fields
  [values & [running]]
  [:fieldset.form-group
   [:legend "Advanced"]
   [:label.form-field
    [:span.form-label (labels :nosql-type)]
    (create/select "nosql-type" session-stores (str (get values :nosql-type)))
    (restart-hint :nosql-type "Where login sessions are kept." running values)]
   [:div.form-row
    (input values running :nosql-host "Only used for Valkey or Redis.")
    (input values running :nosql-port "Only used for Valkey or Redis." {:type "number" :min 1 :max 65535})]
   [:label.checkbox
    [:input {:type "checkbox" :name "debug" :value "1" :checked (boolean (get values :debug))}]
    [:span "Debug mode — enables " [:code "/api/echo"] " for admins, which prints raw requests. Leave off in production."]]])

;;; Settings tab

(defn config-card
  "The Server configuration tab. VALUES default to the saved settings."
  [& [message errors values]]
  (let [running (s/get-settings)
        values (or values (s/file-settings))]
    [:section#config-card.card
     [:div.card-header [:h2 "Server configuration"]]
     [:p.muted "Saved to " [:code (s/config-path)] ". Settings marked as applying after a restart "
      "take effect the next time Block Shipper starts."]
     (when message (ui/alert (:kind message) (:text message)))
     (ui/errors-alert errors)
     [:form.form {:hx-post "/api/global/config" :hx-target "#config-card" :hx-swap "outerHTML"
                  :style "margin-top: 1rem"}
      (general-fields values running)
      (storage-fields values running)
      (minecraft-fields values running)
      (advanced-fields values running)
      [:div.form-actions [:button.btn.btn-primary {:type "submit"} "Save"]]]]))

(defn api-save
  [req _me]
  (let [params (:params req)
        [settings errors] (parse params)
        errors (if (seq errors) errors (create-folders! settings))]
    (if (seq errors)
      (r/h-respond (config-card nil errors (form-values params)))
      (do (s/save-settings! settings)
          (let [pending (s/pending-restart)]
            (r/h-respond
             (config-card {:kind :success
                           :text (if (seq pending)
                                   (str "Saved. Restart Block Shipper to apply: "
                                        (str/join ", " (map labels pending)) ".")
                                   "Saved.")})))))))
