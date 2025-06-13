(ns com.blockshipper.response
  (:require [hiccup2.core :as h]))

(defn respond-generic
  "Generic response with the given BODY and CONTENT-TYPE."
  [body content-type]
  {:status 200
   :body body
   :headers {"Content-Type" content-type}})

(defn respond
  "Make a basic html response with the given HTML string."
  [html]
  {:status 200
   :body html
   :headers {"Content-Type" "text/html; charset=UTF-8"}})

(defn h-respond
  "Make a basic hiccup html response with the given HIC-HTML."
  [hic-html]
  (respond (str (h/html hic-html))))
