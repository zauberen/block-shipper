(ns com.blockshipper.database.sqlite
  (:require [clojure.java.io :as io]
            [com.blockshipper.database.migration :as m]
            [com.blockshipper.settings :as s]
            [com.blockshipper.wrappers.path-utils :as pu]))

(def db-spec {:classname "org.sqlite.JDBC"
              :subprotocol "sqlite"
              :subname (pu/expand-home-path (:sqlite-db-path (s/get-settings)))})

(defn initialize
  "Make sure that the DB is ready, running any required migrations."
  []
  (if (not (.exists (io/file (pu/expand-home-path (:sqlite-db-path (s/get-settings))))))
    (m/run-migrations db-spec)
    (m/check-run-migrations db-spec)))
