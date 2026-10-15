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

(ns com.blockshipper.pages.servers.create
  "Creating a new server (admin only)."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.blockshipper.minecraft.provider :as provider]
            [com.blockshipper.minecraft.setup :as setup]
            [com.blockshipper.minecraft.world-import :as world-import]
            [com.blockshipper.pages.layout :as layout]
            [com.blockshipper.response :as r]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.settings :as s]
            [com.blockshipper.ui :as ui]
            [com.blockshipper.uploads :as uploads]
            [com.blockshipper.util :as u]))

(defn- versions
  "Available versions, or nil when they cannot be fetched (offline)."
  []
  (try
    (provider/available-versions :vanilla)
    (catch Exception e
      (log/warn "Could not fetch the version list:" (.getMessage e))
      nil)))

(defn field
  [label input & [hint]]
  [:label.form-field
   [:span.form-label label]
   input
   (when hint [:span.form-hint hint])])

(defn select
  [name options selected & [attrs]]
  (into [:select.form-input (merge {:name name} attrs)]
        (for [[value label] options]
          [:option {:value value :selected (= (str value) (str selected))} label])))

(def host-mode-options
  [["published" "Published — start when requested, stop after 15 idle minutes"]
   ["always-active" "Always active — keep running"]
   ["disabled" "Disabled"]])

(def list-mode-options
  [["blacklist" "Ban list — anyone not banned can join"]
   ["whitelist" "Whitelist — only listed players can join"]])

(defn create-form
  "The create server form. VALUES may carry :world_upload, the id of a world archive
  that was uploaded before validation failed, so it isn't sent again."
  [values errors & [user]]
  (let [settings (s/get-settings)
        versions (versions)
        staged (when-let [id (:world_upload values)] (uploads/info id (:id user)))]
    ;; main.js uploads the chosen world in pieces (data-world-upload) before submitting.
    [:form.card.form {:hx-post "/api/servers" :hx-target "this" :hx-swap "outerHTML"
                      :hx-disabled-elt "find button[type=submit]"
                      :data-world-upload "/api/uploads"}
     (ui/errors-alert errors)
     (field "Display name"
            [:input.form-input {:name "display_name" :required true :value (:display_name values)
                                :placeholder "Survival"
                                :oninput "const id=this.form.elements['name']; if(!id.dataset.touched){id.value=this.value.toLowerCase().replace(/[^a-z0-9_-]+/g,'-').replace(/^[-_]+|[-_]+$/g,'').slice(0,32)}"}])
     (field "Server id"
            [:input.form-input {:name "name" :required true :value (:name values) :pattern "[a-z0-9][a-z0-9_\\-]{0,31}"
                                :oninput "this.dataset.touched='1'"}]
            "Used for the folder name and URLs. Lowercase letters, numbers, dashes and underscores.")
     (field "Server type"
            (select "server_type" [["vanilla" "Vanilla"]] "vanilla")
            "Modded servers are planned for a later version.")
     (field "Minecraft version"
            (if versions
              (select "mc_version" (map (fn [v] [v v]) versions) (or (:mc_version values) (first versions)))
              [:input.form-input {:name "mc_version" :required true :value (:mc_version values)
                                  :placeholder "e.g. 1.21.4"}])
            (when-not versions "The version list could not be loaded; type a version number."))
     [:div.form-row
      (field "Memory (min)" [:input.form-input {:name "mem_min" :value (or (:mem_min values) (:mc-mem-min settings))}])
      (field "Memory (max)" [:input.form-input {:name "mem_max" :value (or (:mem_max values) (:mc-mem-max settings))}])]
     (field "Public port"
            [:input.form-input {:name "exposed_port" :type "number" :min 1 :max 65535 :value (:exposed_port values)
                                :placeholder (str (servers/next-free-port servers/first-exposed-port (servers/used-ports)))}]
            "Players connect to this port. Leave blank to pick the next free one.")
     (field "Hosting" (select "host_mode" host-mode-options (or (:host_mode values) "published")))
     (field "Player list" (select "list_mode" list-mode-options (or (:list_mode values) "blacklist")))
     (field "World (optional)"
            ;; No name: the file is sent by main.js, only its upload id goes with the form.
            [:input.form-input {:type "file" :data-world-file true
                                :accept ".zip,.jar,.tar,.gz,.tgz,.tar.gz,.7z"}]
            (if staged
              (str "Already uploaded: " (:filename staged) ". Choose another file to replace it.")
              (str "An existing world as a " world-import/supported-formats
                   " archive. The folder containing level.dat is used. Leave empty to generate a new world.")))
     [:input {:type "hidden" :name "world_upload" :value (when staged (:world_upload values))}]
     [:div.upload-status {:hidden true}
      [:progress.upload-progress {:max 100 :value 0}]
      [:span.upload-status-text.form-hint {:role "status" :aria-live "polite"}]]
     [:label.checkbox
      [:input {:type "checkbox" :name "eula" :value "1" :required true}]
      [:span "I accept the "
       [:a {:href "https://aka.ms/MinecraftEULA" :target "_blank" :rel "noopener"} "Minecraft EULA"]
       " on behalf of this server."]]
     [:div.form-actions
      [:button.btn.btn-primary {:type "submit"} "Create server"]]]))

(defn create-page
  [req user]
  (layout/app-page req user
                   (layout/main
                    [:h1.dashboard-title "Create Server"]
                    (create-form {} nil))))

(defn- form->attrs
  [params]
  {:display_name (some-> (:display_name params) str/trim)
   :name (some-> (:name params) str/trim)
   :server_type (or (:server_type params) "vanilla")
   :mc_version (some-> (:mc_version params) str/trim not-empty)
   :mem_min (some-> (:mem_min params) str/trim not-empty)
   :mem_max (some-> (:mem_max params) str/trim not-empty)
   :exposed_port (u/parse-int (:exposed_port params))
   :host_mode (:host_mode params)
   :list_mode (:list_mode params)})

(def ^:private mem-re #"^\d+[KMGkmg]?$")

(def version-re
  "Minecraft version ids, e.g. 1.21.4 or 25w14a."
  #"[A-Za-z0-9._ -]{1,40}")

(defn- user-message
  [^clojure.lang.ExceptionInfo e]
  (or (:user-message (ex-data e)) (.getMessage e)))

(defn- prepare-world
  "Claim and extract the uploaded world. Returns [upload prepared nil] or
  [upload nil error-message]; upload is the claimed file (nil when claiming failed)."
  [upload-id user]
  (let [[upload error] (try [(:file (uploads/take! upload-id (:id user))) nil]
                            (catch clojure.lang.ExceptionInfo e [nil (user-message e)]))]
    (if error
      [nil nil error]
      (try
        [upload (world-import/prepare upload) nil]
        (catch clojure.lang.ExceptionInfo e
          [upload nil (user-message e)])
        (catch Exception e
          (io/delete-file upload true)
          (throw e))))))

(defn api-create
  [req user]
  (let [params (:params req)
        attrs (form->attrs params)
        errors (cond-> (servers/validate attrs nil)
                 (str/blank? (:name attrs)) (conj "A server id is required.")
                 (nil? (:mc_version attrs)) (conj "Choose a Minecraft version.")
                 (and (:mc_version attrs) (not (re-matches version-re (:mc_version attrs))))
                 (conj "That doesn't look like a version.")
                 (and (:mem_min attrs) (not (re-matches mem-re (:mem_min attrs)))) (conj "Memory must look like 512M or 4G.")
                 (and (:mem_max attrs) (not (re-matches mem-re (:mem_max attrs)))) (conj "Memory must look like 512M or 4G.")
                 (not= "1" (:eula params)) (conj "You must accept the Minecraft EULA."))
        upload-id (some-> (:world_upload params) str/trim not-empty)]
    (if (seq errors)
      (r/h-respond (create-form (assoc attrs :world_upload upload-id) errors user))
      (let [[upload world world-error] (when upload-id (prepare-world upload-id user))]
        (try
          (if world-error
            (r/h-respond (create-form attrs [world-error] user))
            (let [version (:mc_version attrs)
                  server (servers/create! (-> attrs
                                              (dissoc :mc_version)
                                              (assoc :user_id (:id user))
                                              (->> (remove (comp nil? val)) (into {}))))]
              (setup/prepare-dir! server)
              (when world
                (world-import/install! world (servers/server-dir server)))
              (setup/install-async! server version)
              (servers/notify-changed! (:id server))
              {:status 200
               :headers {"HX-Redirect" (str "/admin/servers/" (:name server))}
               :body ""}))
          (finally
            (world-import/cleanup! world)
            (when upload (io/delete-file upload true))))))))

;; Chunked world uploads (see com.blockshipper.uploads and bsUploadWorld in main.js).

(defn- upload-error
  [status msg]
  {:status status :headers {"Content-Type" "text/plain; charset=utf-8"} :body msg})

(defn api-upload-start
  "POST /api/uploads with filename and size; responds with {id, chunkSize}."
  [req user]
  (let [{:keys [filename size]} (:params req)]
    (try
      (let [{:keys [id chunk-bytes]} (uploads/start! (:id user) filename (u/parse-int size))]
        (r/respond-generic (json/write-str {:id id :chunkSize chunk-bytes}) "application/json"))
      (catch clojure.lang.ExceptionInfo e
        (upload-error 400 (user-message e))))))

(defn api-upload-chunk
  "PUT /api/uploads/:id?offset=N with the raw bytes of one piece; responds with the
  bytes received so far (409 with that count when OFFSET doesn't match)."
  [req user id]
  (let [offset (u/parse-int (get-in req [:params :offset]))]
    (try
      (if (nil? offset)
        (upload-error 400 "Missing offset.")
        (let [result (uploads/append! id (:id user) offset (:body req))]
          (if (map? result)
            (upload-error 409 (str (:conflict result)))
            (r/respond-generic (str result) "text/plain"))))
      (catch clojure.lang.ExceptionInfo e
        (upload-error 400 (user-message e))))))

(defn api-upload-delete
  [_req user id]
  (uploads/discard! id (:id user))
  {:status 204 :body ""})
