(ns com.blockshipper.pages.login
  (:require [com.blockshipper.ui :as ui]
            [com.blockshipper.response :as r]
            [com.blockshipper.settings :as s]))

(defn login-page
  "Returns the login page as a ring response."
  []
  (r/h-respond
   (ui/std-header
    (ui/link-stylesheet "/css/login.css")
    [:body
     [:div.login-container {:hx-get "/api/null" :hx-trigger "load" :hx-push-url "/login" :hx-swap "none"}
      [:h1.app-name (:app-name (s/get-settings))]
      [:div#error-message.error-message "Username or password incorrect"]
      [:form#login-form {:hx-post "/api/login"
                         :hx-target "#login-form"
                         :hx-swap "outerHTML"
                         :hx-indicator "#login-button"}
       [:div.form-group
        [:label.form-label {:for "username"}
         "Username"]
        [:input#username.form-input {:type "text"
                                     :name "username"
                                     :placeholder "Enter your username"
                                     :required ""
                                     :autocomplete "username"}]]
       [:div.form-group
        [:label.form-label {:for "password"}
         "Password"]
        [:input#password.form-input {:type "password"
                                     :name "password"
                                     :placeholder "Enter your password"
                                     :required ""
                                     :autocomplete "current-password"}]]
       [:div.button-container
        [:button#login-button.login-button {:type "submit"
                                            :hx-disable-elt "this"
                                            :hx-indicator "this"}
         [:span.button-text "Log In"]]]]]
     (ui/link-js-script "/js/login.js")])))

