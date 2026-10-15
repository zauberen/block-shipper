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

;; A login refused by the whitelist is logged as "Disconnecting Steve (/1.2.3.4:5678): You
;; are not white-listed on this server!" and "Steve (/1.2.3.4:5678) lost connection: ...";
;; older versions print a GameProfile ("...[id=<uuid>,name=Steve,...] (/1.2.3.4:5678)")
;; instead of the name. A player kicked in game has no address, so isn't matched.
(def ^:private not-whitelisted-re #"(?i).*\bnot white-?listed\b.*")
(def ^:private rejected-name-res
  [(re-pattern (str "^(?:Disconnecting )?(?:com\\.mojang\\.authlib\\.)?GameProfile.*?\\bname=" player "\\b.*\\(/"))
   (re-pattern (str "^(?:Disconnecting )?" player " \\(/"))])
(def ^:private profile-uuid-re #"\bid=([0-9a-fA-F]{8}-[0-9a-fA-F-]{27})\b")

(defn- rejected-login
  [msg]
  (when (re-matches not-whitelisted-re msg)
    (when-let [player (some #(second (re-find % msg)) rejected-name-res)]
      (cond-> {:type :rejected :player player}
        (re-find profile-uuid-re msg) (assoc :uuid (second (re-find profile-uuid-re msg)))))))

(defn message
  "The message part of a console LINE, or nil when the line has no log prefix."
  [line]
  (second (re-matches message-re line)))

(defn parse-line
  "Returns an event map for LINE, or nil when the line is not interesting.
  Events: {:type :ready}, {:type :join :player p}, {:type :leave :player p},
  {:type :uuid :player p :uuid u}, {:type :stopping}, and {:type :rejected :player p}
  (with :uuid when the line has one) for a login the whitelist refused."
  [line]
  (when-let [msg (some-> line message)]
    (or (some (fn [[type re]]
                (when-let [m (re-matches re msg)]
                  (case type
                    (:join :leave) {:type type :player (second m)}
                    :uuid {:type :uuid :player (nth m 1) :uuid (nth m 2)}
                    {:type type})))
              patterns)
        (rejected-login msg))))
