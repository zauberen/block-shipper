(ns com.main
  (:gen-class)
  (:require [com.blockshipper :as bs]))

(defn -main [& _]
  (bs/start-server))

(defn -stop []
  (bs/stop-server))
