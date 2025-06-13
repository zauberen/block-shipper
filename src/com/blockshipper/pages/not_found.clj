(ns com.blockshipper.pages.not-found
  (:require [com.blockshipper.settings :as s]
            [com.blockshipper.response :as r]
            [com.blockshipper.ui :as ui]))

(defn not-found-html
  "Get the 404 page."
  []
  (r/h-respond
   (ui/std-header
    (ui/link-stylesheet "/css/notfound.css")
    [:body
     [:a.app-logo {:href "/"}
      (:app-name (s/get-settings))]
     [:div.floating-shapes [:div.shape] [:div.shape] [:div.shape]]
     [:div.error-container
      [:div.error-code "404"]
      [:h1.error-title "Page Not Found"]
      [:p.error-message
       "The page you're looking for doesn't exist or has been moved. Let's get you back on track."]
      [:div.button-group
       [:a.btn.btn-primary {:href "/"}
        "Go Home"]
       [:a.btn.btn-secondary {:href "javascript:history.back()"}
        "Go Back"]]]])))
