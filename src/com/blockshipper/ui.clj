(ns com.blockshipper.ui
  (:require [clojure.java.io :as io]
            [ring.util.response :as ring-response]
            [com.blockshipper.settings :as s]))

(defn static-path [path]
  (if-some [last-modified (some-> (io/resource (str "public" path))
                                  ring-response/resource-data
                                  :last-modified
                                  (.getTime))]
    (str path "?t=" last-modified)
    path))

(defn link-stylesheet
  "Generates a stylesheet link for the given resource."
  [resource-path]
  [:link {:rel "stylesheet" :type "text/css" :href (static-path resource-path)}])

(defn link-js-script
  "Generates a script link for the given resource."
  [resource-path]
  [:script {:src (static-path resource-path)}])

(defn std-header
  "Generates the header to wrap your BODY content in, add SCRIPTS to header."
  [scripts body]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "UTF-8"}
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}
      [:title (:app-name (s/get-settings))]
      [:link {:rel "stylesheet" :type "text/css" :href (static-path "/css/theme.css")}]
      (when (not (nil? scripts)) scripts)
      [:script
       {:src "https://unpkg.com/htmx.org@2.0.4"
        :integrity
        "sha384-HGfztofotfshcF7+8n44JQL2oJmowVChPTg48S+jvZoztPfvwD79OC/LTtG6dMp+"
        :crossorigin "anonymous"}]
      [:script
       {:src "https://unpkg.com/htmx-ext-ws@2.0.2"
        :integrity
        "sha384-vuKxTKv5TX/b3lLzDKP2U363sOAoRo5wSvzzc3LJsbaQRSBSS+3rKKHcOx5J8doU"
        :crossorigin "anonymous"}]]]]
   body])

