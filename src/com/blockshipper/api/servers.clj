(ns com.blockshipper.api.servers
  (:require [clojure.java.shell :refer [sh]]
            [com.blockshipper.database.db :as db]
            [com.blockshipper.wrappers.path-utils :as pu]
            [com.blockshipper.response :as r]
            [com.blockshipper.settings :as s]
            [com.blockshipper.util :as u]))

(defn get-list
  "Returns a list of all minecraft servers in the host directory."
  [settings-map]
  (pu/list-directory-names (:hosting-root settings-map)))

(defn online?
  "Check if the given SERVER is online."
  [server]
  (let [screen-list (:out (sh "screen" "-ls"))]
    (or (.contains screen-list (str "." server "\t(Detached)"))
        (.contains screen-list (str "." server "\t(Attached)")))))

(defn get-status
  "Returns the string status of SERVER."
  [server]
  (if (online? server)
    "online"
    "offline"))

(defn gen-sidebar-server-h
  "Creates a hiccup html entry for the left hand sidebar for the given SERVER-NAME with the given ONLINE-STATUS."
  [server-name online-status]
  [:button.nav-button [:div.server-status {:class online-status}] server-name])


(defn build-sidebar-server-list
  "Creates the inner HTML for the left hand sidebar."
  [settings-map]
  (mapv (fn [server] (gen-sidebar-server-h server (get-status server)))
        (get-list settings-map)))

; Bound to api/servers/sidebar
(defn api-sidebar-server-list
  "Get the sidebar server list for the api."
  []
  (r/h-respond
   (u/vconcat [:span]
              (build-sidebar-server-list (s/get-settings)))))

(defn gen-statistic-h
  "Creates a hiccup html statistic for the main dashboard widgets with the given LABEL and VALUE."
  [label value]
  [:div.stat-item [:span.stat-label label] [:span.stat-value value]])

(defn gen-statistics-h-vec
  "Create the statistic vector list for SERVER-NAME using DB-SPEC."
  [server-name db-spec]
  ())

(defn gen-main-dashboard-server-h
  "Creates a hiccup html entry for the main dashboard for the given SERVER-NAME with the given ONLINE-STATUS using the given DB-SPEC to query the statistics."
  [server-name online-status db-spec]
  [:div.server-widget
   [:div.widget-header
    [:div.widget-title
     [:div.server-status {:class online-status}]
     [:span.widget-name server-name]]]
   (u/vconcat [:div.widget-stats]
              (gen-statistics-h-vec server-name db-spec))
   [:div.chart-container [:canvas#webserverchart]]])

(defn gen-main-dashboard-h
  "Generate the hiccup HTML dashboard widgets."
  [settings-map]
  (u/concat-mapv [:div.server-grid]
                 (fn [server]
                   (gen-main-dashboard-server-h
                    server
                    (get-status server)
                    (db/get-db-spec)))
                 (get-list settings-map)))

(defn start-server
  "Start the given server."
  [server settings-map]
  (when (not (online? server))
    (sh "screen" (str "-dmS " server " bash -c 'java "
                      (:hosting-root settings-map)
                      "/" server
                      "/minecraft.jar "
                      "-Xms 256M "
                      "-Xmx 4G"
                      "'"))))
