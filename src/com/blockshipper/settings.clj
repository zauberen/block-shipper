(ns com.blockshipper.settings
  (:require [com.blockshipper.wrappers.path-utils :as pu]))

(defn get-default-settings
  "Returns the default settings as a settings object."
  []
  {:app-name "Block Shipper"
   :minecraft-root-path "~/minecraft"
   :db 'sqlite
   :sqlite-db-path "~/minecraft/settings.db"
   :hosting-root "~/minecraft/hosted"
   :backup-root "~/minecraft/backups"
   :logs-root "~/minecraft/logs"
   :host-method 'screen
   :listen-port 8081
   :nosql-type 'valkey
   :nosql-port 6379
   :nosql-host "localhost"
   ;; Used only to start the server, if your port or host are different, make sure to configure those too.
   :nosql-conf nil
   ;;; Development variables
   ;; Whether to include debugging functionality
   :debug true})

(defn get-settings
  "Returns the settings object loaded from the settings file."
  []
  (try
    (if (pu/file-exists? "~/.blockshipper")
      (let [default-settings (get-default-settings)
            base-settings (read-string (pu/read-file-safe "~/.blockshipper"))]
        ; Set defaults when a setting is nil
        (map (fn [skey] (if (contains? base-settings key)
                          (get base-settings key)
                          (get default-settings skey)))
             (keys default-settings)))
      (get-default-settings))
    (catch Exception e
      (println (str "Error converting ini: " (.getMessage e)))
      (.printStackTrace e)
      (get-default-settings))))

(defn get-logfile
  "Returns the log filename as a string. Needs to be used with logs-root to get the full path."
  []
  (let [date (java.util.Date.)]
    (str (format "%04d%02d%02d" (.getYear date) (.getMonth date) (.getDate date))
         "-blockshipper.log")))
