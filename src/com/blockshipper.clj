(ns com.blockshipper
  (:require [clojure.pprint :as pprint]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [compojure.core :as cjr]
            [compojure.route :as route]
            [com.blockshipper.api.servers :as srv]
            [com.blockshipper.api.session :as session]
            [com.blockshipper.minecraft :as mc]
            [com.blockshipper.pages.home :as home]
            [com.blockshipper.pages.login :as lp]
            [com.blockshipper.pages.not-found :as nf]
            [com.blockshipper.response :as r]
            [com.blockshipper.settings :as s]))

(defonce ^:private server (atom nil))


(cjr/defroutes
  routes
  ;; API Bindings
  ; When debugging, you are able to print requests to the repl
  (when (:debug (s/get-settings))
    (cjr/ANY "/api/echo" req
      (r/respond-generic (with-out-str (pprint/pprint req))
                         "text/plain")))
  ; Do-nothing endpoint for htmx hackery
  (cjr/GET
    "/api/null" []
    (r/respond-generic "" "text/plain"))
  ; Try logging into the application, requires username and password parameters
  (cjr/POST
    "/api/login" req
    (session/try-login req))
  ; Get the server list html
  (cjr/GET
    "/api/servers/sidebar" req
    (session/requires-session (fn [_] (srv/api-sidebar-server-list)) req))
  ;; Page bindings
  ; Home dashboard
  (cjr/GET
    "/" req
    (session/requires-session (fn [req] (home/load-homepage req)) req))
  ; Login page
  (cjr/GET
    "/login" []
    (lp/login-page))
  (route/not-found (nf/not-found-html)))

(def app
  (let [wrap-res-fun (fn [handler] (wrap-resource handler "public"))]
    (-> (fn [req] (routes req))
       wrap-keyword-params
       wrap-params
       wrap-res-fun
       wrap-cookies)))

(defn start-server
  "Start the web service."
  []
  (mc/create-directories)
  (reset! server (jetty/run-jetty (fn [req] (app req)) {:port (:listen-port (s/get-settings)) :join? false})))

(defn stop-server
  "Stop the web service."
  []
  (when-some [s @server]
    (.stop s)
    (reset! server nil)))
