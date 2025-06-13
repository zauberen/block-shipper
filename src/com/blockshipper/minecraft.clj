(ns com.blockshipper.minecraft
  (:require [com.blockshipper.wrappers.path-utils :as p]
            [com.blockshipper.settings :as s]))

(defn create-directories
  "Create all of the required directories for the application to function."
  []
  (p/create-dir (:minecraft-root-path (s/get-settings)))
  (p/create-dir (:hosting-root (s/get-settings)))
  (p/create-dir (:backup-root (s/get-settings)))
  (p/create-dir (:logs-root (s/get-settings))))
