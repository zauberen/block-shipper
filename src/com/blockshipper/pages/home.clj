(ns com.blockshipper.pages.home
  (:require [com.blockshipper.api.servers :as server]
            [com.blockshipper.api.session :as sn]
            [com.blockshipper.response :as r]
            [com.blockshipper.settings :as s]
            [com.blockshipper.ui :as ui]
            [com.blockshipper.util :as u]))

(defn settings-button
  "Returns the hiccup rendition of the settings svg icon."
  []
  [:svg.header-icon.settings-icon {:fill "none"
                                   :stroke "currentColor"
                                   :viewbox "0 0 24 24"}
   [:path
    {:stroke-linecap "round"
     :stroke-linejoin "round"
     :stroke-width "2"
     :d
     "M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"}]
   [:path {:stroke-linecap "round"
           :stroke-linejoin "round"
           :stroke-width "2"
           :d "M15 12a3 3 0 11-6 0 3 3 0 016 0z"}]])

(defn logout-button
  "Returns the hiccup rendition of the logout svg icon."
  []
  [:svg.header-icon.logout-icon
   {:fill "none"
    :stroke "currentColor"
    :viewbox "0 0 24 24"
    :onclick
    "window.location.href='/logout';"}
   [:path
    {:stroke-linecap "round"
     :stroke-linejoin "round"
     :stroke-width "2"
     :d
     "M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"}]])

(defn power-icon
  "Returns the hiccup power svg icon with the given SIZE."
  [size]
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :width size
         :height size
         :viewbox "0 0 32 32"}
   [:path
    {:fill "currentColor"
     :d
     "M25.542 8.354c-1.47-1.766-2.896-2.617-3.025-2.695a1.99 1.99 0 0 0-2.74.723c-.555.96-.24 2.194.706 2.763h.002l.003.003h.004c.102.062 1.124.73 2.08 1.925c1.003 1.26 1.933 3.017 1.937 5.438a9.122 9.122 0 0 1-2.64 6.438c-1.638 1.653-3.878 2.67-6.37 2.67c-2.49 0-4.73-1.017-6.368-2.67a9.105 9.105 0 0 1-2.64-6.437c.005-2.5.995-4.292 2.035-5.558a9.678 9.678 0 0 1 1.425-1.4c.19-.153.346-.264.445-.33l.104-.07l.013-.007l.004-.002a2.036 2.036 0 0 0 .705-2.763a1.991 1.991 0 0 0-2.74-.724c-.127.078-1.554.93-3.023 2.695c-1.463 1.75-2.975 4.51-2.97 8.157c0 7.263 5.825 13.152 13.01 13.155c7.186-.003 13.01-5.892 13.012-13.155c.004-3.648-1.507-6.407-2.97-8.158zM15.5 17.524a2.015 2.015 0 0 0 2.002-2.024V3.357c0-1.118-.897-2.024-2.002-2.024s-2.002.906-2.002 2.024V15.5c0 1.116.897 2.023 2.002 2.023z"}]])


(defn add-icon
  "Returns the hiccup add + svg icon."
  []
  [:svg.nav-icon {:fill "none" :stroke "currentColor" :viewbox "0 0 24 24"}
   [:path {:stroke-linecap "round"
           :stroke-linejoin "round"
           :stroke-width "2"
           :d "M12 6v6m0 0v6m0-6h6m-6 0H6"}]])

(defn home-icon
  "Returns the hiccup home svg icon."
  []
  [:svg.nav-icon {:fill "none" :stroke "currentColor" :viewbox "0 0 24 24"}
   [:path
    {:stroke-linecap "round"
     :stroke-linejoin "round"
     :stroke-width "2"
     :d
     "M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6"}]])

(defn load-homepage
  "Returns the home dashboard html."
  [req]
  (let [session-map (sn/get-session req)
        settings-map (s/get-settings)]
    (r/h-respond
     (ui/std-header
      (ui/link-stylesheet "css/main.css")
      [:body
       [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/Chart.js/3.9.1/chart.min.js"}]
       [:header.header
        [:a.app-logo {:href "/"} (:app-name settings-map)]
        [:div.user-section
         [:span.username (:user session-map)]
         (settings-button)
         (logout-button)]]
       [:aside.sidebar
        [:div.nav-section
         [:a.nav-button.active {:href "/"} (home-icon) "Home"]
         [:button.nav-button (add-icon) "Create Server"]]
        [:div.section-divider
         [:div.section-header
          [:div.section-label "Servers"]
          [:button.shutdown-button
           {:onclick
            "if(confirm('Are you sure you want to shutdown all servers?')) { alert('Shutting down all servers...'); }"}
           (power-icon "12")
           "Shutdown All"]]]
        (u/vconcat [:div#server-list {:hx-get "/api/servers/sidebar"
                                           :hx-trigger "load, every 10s"
                                           :hx-swap "innerHTML"}]
              (server/build-sidebar-server-list settings-map))]
       [:main.main-content
        [:h1.dashboard-title "Server Dashboard"]
        (server/gen-main-dashboard-h settings-map)]
       (ui/link-js-script "/js/main.js")]))))

