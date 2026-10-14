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

(ns com.blockshipper.minecraft.log-parser
  "Turns minecraft server console lines into events.")

(def ^:private message-re
  ;; [12:34:56] [Server thread/INFO]: message  (optionally with a [logger] section before the colon)
  #"^\[[^\]]*\] \[[^\]]*/[A-Z]+\](?: \[[^\]]*\])?: (.*)$")

(def ^:private player "([A-Za-z0-9_]{1,16})")

;; Newer versions (26.x) log join/leave messages as "System chat: Steve joined the game".
(def ^:private system-chat "(?:System chat: )?")

(def ^:private patterns
  [[:ready  #"^Done \([0-9.,]+s\)!.*"]
   [:join   (re-pattern (str "^" system-chat player "(?: \\(formerly known as [A-Za-z0-9_]+\\))? joined the game$"))]
   [:leave  (re-pattern (str "^" system-chat player " left the game$"))]
   [:uuid   (re-pattern (str "^UUID of player " player " is ([0-9a-fA-F-]{32,36})$"))]
   [:stopping #"^Stopping (?:the )?server$"]])

(defn message
  "The message part of a console LINE, or nil when the line has no log prefix."
  [line]
  (second (re-matches message-re line)))

(defn parse-line
  "Returns an event map for LINE, or nil when the line is not interesting.
  Events: {:type :ready}, {:type :join :player p}, {:type :leave :player p},
  {:type :uuid :player p :uuid u}, {:type :stopping}."
  [line]
  (when-let [msg (some-> line message)]
    (some (fn [[type re]]
            (when-let [m (re-matches re msg)]
              (case type
                (:join :leave) {:type type :player (second m)}
                :uuid {:type :uuid :player (nth m 1) :uuid (nth m 2)}
                {:type type})))
          patterns)))
