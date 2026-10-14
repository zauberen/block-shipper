;; Block Shipper - run and manage Minecraft servers from a web dashboard.
;; Copyright (C) 2025-2026 zauberen
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU Affero General Public License as published
;; by the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.

(ns com.blockshipper.pages.servers.console
  "Live console: streams log lines over a websocket and accepts commands."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hiccup2.core :as h]
            [ring.websocket :as ws]
            [com.blockshipper.auth :as auth]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.pages.servers.page :as page]))

(defn- line-h
  [line]
  [:div.log-line {:class (when (str/starts-with? line "> ") "command")} line])

(defn console-tab
  [_req _user server]
  (let [running? (inst/running? (:id server))]
    [:div.tab-body {:hx-ext "ws" :ws-connect (str "/ws/servers/" (:name server) "/console")}
     (if running?
       [:pre#console-log.log-view.console {:data-autoscroll "true"}
        (map line-h (inst/log-lines (:id server)))]
       [:div
        [:p.muted "The server is offline. Showing the end of the last log; start the server to use the console."]
        [:pre#console-log.log-view.console {:data-autoscroll "true"}
         (map line-h (page/latest-log-tail server 300))]])
     [:form.inline-form.console-form {:ws-send true}
      [:input.form-input {:name "command" :autocomplete "off" :required true
                          :placeholder (if running? "Type a command, e.g. say Hello or list" "Server is offline")
                          :disabled (not running?)}]
      [:button.btn.btn-primary {:type "submit" :disabled (not running?)} "Send"]]]))

(defn- oob-lines
  [lines]
  (str (h/html [:div#console-log {:hx-swap-oob "beforeend"} (map line-h lines)])))

(defn console-socket
  "Websocket handler for SERVER's console."
  [req user server]
  (if-not (ws/upgrade-request? req)
    {:status 400 :headers {"Content-Type" "text/plain"} :body "Websocket expected."}
    (let [server-id (:id server)
          listener-key (Object.)]
      {::ws/listener
       {:on-open (fn [socket]
                   (inst/add-listener!
                    listener-key
                    (fn [sid event]
                      (when (= sid server-id)
                        (case (:type event)
                          :log (try (ws/send socket (oob-lines [(:line event)]))
                                    (catch Exception _ (inst/remove-listener! listener-key)))
                          :state (when (= :offline (:state event))
                                   (try (ws/send socket (oob-lines ["[Block Shipper] Server stopped."]))
                                        (catch Exception _ nil)))
                          nil)))))
        :on-message (fn [_socket message]
                      (let [command (some-> (try (get (json/read-str (str message)) "command")
                                                 (catch Exception _ nil))
                                            str/trim
                                            (str/replace #"^/" ""))]
                        ;; Re-check access in case permissions changed since the socket opened.
                        (when (and (seq command)
                                   (auth/can-access-server? (auth/current-user req) server))
                          (log/info (:username user) "ran console command on" (:name server) ":" command)
                          (inst/send-command! server-id command))))
        :on-close (fn [_socket _code _reason]
                    (inst/remove-listener! listener-key))
        :on-error (fn [_socket _err]
                    (inst/remove-listener! listener-key))}})))

(page/register-tab! "console" console-tab)
