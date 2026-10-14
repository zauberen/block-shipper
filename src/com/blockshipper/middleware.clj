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

(ns com.blockshipper.middleware
  "Ring middleware that hardens every response: cross-origin request checks, a request
  body size limit and security headers."
  (:require [clojure.string :as str])
  (:import [java.net URI]))

(def max-body-bytes
  "Largest request body accepted outside of the paths in `large-body-paths`."
  (* 1024 1024))

(def large-body-paths
  "Paths that take uploads (world archives on the create server form)."
  #{"/api/servers"})

(defn- default-port
  [scheme]
  (case scheme "https" 443 "http" 80 nil))

(defn- parse-host
  "[host port] from a Host header value such as example.com:8081 or [::1]:8081.
  The port is nil when absent."
  [^String host]
  (when-not (str/blank? host)
    (let [[_ h p] (re-matches #"(\[[^\]]*\]|[^:]*)(?::(\d+))?" (str/trim host))]
      (when h [(str/lower-case h) (some-> p parse-long)]))))

(defn- parse-origin
  "[host port] of an Origin header, with the scheme's default port filled in."
  [^String origin]
  (try
    (let [uri (URI. origin)
          host (.getHost uri)]
      (when host
        [(str/lower-case host)
         (if (neg? (.getPort uri)) (default-port (.getScheme uri)) (.getPort uri))]))
    (catch Exception _ nil)))

(defn- matches-host?
  "True when ORIGIN ([host port]) is the host named by HOST-HEADER. A Host header
  without a port (common behind a reverse proxy) only has to match the host name."
  [[o-host o-port] host-header]
  (when-let [[h-host h-port] (parse-host host-header)]
    (and (= o-host h-host)
         (or (nil? h-port) (= o-port h-port)))))

(defn same-origin?
  "False when REQ carries an Origin header naming another site than the one it was sent
  to. Requests without an Origin (non-browser clients) are allowed."
  [req]
  (let [origin (get-in req [:headers "origin"])]
    (or (nil? origin)
        (when-let [o (parse-origin origin)]
          (boolean (some #(matches-host? o %)
                         (remove nil? [(get-in req [:headers "host"])
                                       (get-in req [:headers "x-forwarded-host"])])))))))

(defn- websocket-upgrade?
  [req]
  (= "websocket" (some-> (get-in req [:headers "upgrade"]) str/lower-case)))

(defn wrap-same-origin
  "Reject state-changing requests and websocket upgrades sent from other sites (CSRF
  and cross-site websocket hijacking). The session cookie is SameSite=Strict, but
  other ports on the same host count as the same site, so this checks the Origin too."
  [handler]
  (fn [req]
    (if (and (or (not (#{:get :head :options} (:request-method req)))
                 (websocket-upgrade? req))
             (not (same-origin? req)))
      {:status 403 :headers {"Content-Type" "text/plain"} :body "Cross-origin request blocked."}
      (handler req))))

(defn wrap-body-limit
  "Refuse request bodies larger than `max-body-bytes` (by Content-Length) except on the
  upload paths, so unauthenticated requests can't make the server buffer huge forms."
  [handler]
  (fn [req]
    (let [length (some-> (get-in req [:headers "content-length"]) str/trim parse-long)]
      (if (and length (> length max-body-bytes) (not (large-body-paths (:uri req))))
        {:status 413 :headers {"Content-Type" "text/plain"} :body "Request too large."}
        (handler req)))))

(def security-headers
  {"X-Content-Type-Options" "nosniff"
   "X-Frame-Options" "DENY"
   "Content-Security-Policy" "frame-ancestors 'none'"
   "Referrer-Policy" "same-origin"})

(defn wrap-security-headers
  "Add `security-headers` to every response that doesn't set them itself."
  [handler]
  (fn [req]
    (when-let [resp (handler req)]
      (update resp :headers #(merge security-headers %)))))
