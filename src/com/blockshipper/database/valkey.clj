(ns com.blockshipper.database.valkey
  (:require [taoensso.carmine :as car]
            [com.blockshipper.settings :as s]))

(defmacro wcar*
  "Macro that reduces the code required to do a nosql operation."
  [& body]
  `(car/wcar {:pool {}
              :spec {:host (:nosql-host (s/get-settings))
                     :port (:nosql-port (s/get-settings))}}
             ~@body))

(defn insert-record
  "Insert a KEY VALUE pair into the nosql server."
  [key value]
  (wcar* (car/set key value)))

(defn insert-record-with-ttl
  "Insert a KEY VALUE pair with TTL-SECONDS (time to live in seconds)."
  [key value ttl-seconds]
  (wcar* (car/setex key ttl-seconds value)))

(defn get-record
  "Retrieve value by KEY from nosql server."
  [key]
  (wcar* (car/get key)))

(defn key-exists?
  "Check if a key exists using EXISTS command."
  [key]
  (= 1 (wcar* (car/exists key))))

(defn ping
  "Test connection to the nosql server."
  []
  (wcar* (car/ping)))

(defn start-server
  "Start the server as a daemon."
  []
  (println "Valkey ping: " (ping)))
