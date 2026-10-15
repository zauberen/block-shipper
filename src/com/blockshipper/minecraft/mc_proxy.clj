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

(ns com.blockshipper.minecraft.mc-proxy
  "Block Shipper listens on each server's public port and forwards players to the
  minecraft server, which only listens on localhost.

  While a server is offline the proxy answers server list pings itself (\"sleeping\")
  and starts the server when someone tries to join. In the kick start method the
  player is disconnected and asked to reconnect once it is up; in the hold start
  method the connection is held open until the server is ready and then forwarded."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.blockshipper.global-settings :as gs]
            [com.blockshipper.minecraft.instances :as inst]
            [com.blockshipper.minecraft.player-sync :as sync]
            [com.blockshipper.minecraft.protocol :as proto]
            [com.blockshipper.players :as players]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.settings :as s])
  (:import [java.io InputStream OutputStream]
           [java.net InetAddress InetSocketAddress ServerSocket Socket SocketException SocketTimeoutException]))

;; server-id -> {:port p :socket ServerSocket} or {:port p :error message}
(defonce listeners (atom {}))

(def handshake-timeout-ms 10000)

;; Clients give up on a silent login after 30 seconds, so stop holding a bit before that.
(def hold-timeout-ms 25000)
(def hold-poll-ms 200)

(defn listener-error
  "Why the listener for SERVER-ID could not be opened, if it failed."
  [server-id]
  (:error (get @listeners server-id)))

(defn- close-quietly [^java.io.Closeable c]
  (when c (try (.close c) (catch Exception _))))

(defn- pipe!
  "Copy IN to OUT until either side closes."
  [^InputStream in ^OutputStream out]
  (let [buffer (byte-array 16384)]
    (try
      (loop []
        (let [n (.read in buffer)]
          (when (pos? n)
            (.write out buffer 0 n)
            (.flush out)
            (recur))))
      (catch Exception _ nil))))

(defn- forward!
  "Connect CLIENT to the server on localhost:PORT, replaying the bytes already read."
  [^Socket client ^bytes already-read port]
  (with-open [target (Socket.)]
    (.connect target (InetSocketAddress. "127.0.0.1" (int port)) 5000)
    (.setTcpNoDelay target true)
    (.setSoTimeout client 0)
    (let [target-out (.getOutputStream target)]
      (proto/write! target-out already-read)
      (let [upstream (future (pipe! (.getInputStream client) target-out)
                             (close-quietly target))]
        (pipe! (.getInputStream target) (.getOutputStream client))
        (close-quietly client)
        @upstream))))

(defn- status-motd
  [server status]
  (let [name (servers/display-name server)]
    (case status
      :starting (str name " is starting…")
      :stopping (str name " is stopping…")
      (cond
        (= "disabled" (:host_mode server)) (str name " is disabled")
        (gs/auto-start?) (str "💤 " name " is sleeping — join to start it")
        :else (str name " is offline")))))

(defn- player-blocked
  "A kick message if PLAYER may not join SERVER (banned or not whitelisted). Players
  turned away by the whitelist are recorded so admins can add them."
  [server player]
  (let [{:keys [whitelist ban]} (sync/desired server)
        has? (fn [entries] (some #(= (str/lower-case player) (str/lower-case (:player_name %))) entries))]
    (cond
      (has? ban) "You are banned from this server."
      (and (= "whitelist" (:list_mode server)) (not (has? whitelist)))
      (do (players/record-rejected-login! (:id server) player nil)
          "You are not whitelisted on this server."))))

(defn login-action
  "What to do with PLAYER who tries to join SERVER while it is not ready, starting it if
  appropriate: {:hold true} to keep the connection until the server is up, or
  {:kick message} to disconnect them."
  [server player]
  (let [status (inst/status (:id server))
        hold? (gs/hold-start?)]
    (cond
      (= "disabled" (:host_mode server)) {:kick "This server is disabled."}
      (and hold? (= :starting status)) {:hold true}
      (= :starting status) {:kick "The server is starting. Try again in a moment!"}
      (= :stopping status) {:kick "The server is stopping. Try again in a minute."}
      (not (gs/auto-start?)) {:kick "The server is offline. Ask an admin or use the Start button on the server page."}
      :else (if-let [blocked (player-blocked server player)]
              {:kick blocked}
              (try
                (let [{:keys [status stopping]} (inst/start! server (str "proxy:" player))]
                  (log/info "Proxy: starting" (:name server) "for" player)
                  (if hold?
                    {:hold true}
                    {:kick (str "Starting " (servers/display-name server) "! Reconnect in about 30 seconds."
                                (when (= :queued status) (str " (Stopping " stopping " first.)")))}))
                (catch clojure.lang.ExceptionInfo e
                  {:kick (or (:user-message (ex-data e)) "The server could not be started.")})
                (catch Exception e
                  (log/error e "Proxy start failed for" (:name server))
                  {:kick "The server could not be started."}))))))

(defn- await-ready
  "Wait until SERVER-ID is ready. Returns :ready, :timeout, or :stopped when the server
  went offline again after it began starting (a crash, or an admin stopped it)."
  [server-id]
  (let [deadline (+ (System/currentTimeMillis) hold-timeout-ms)]
    (loop [seen-starting? false]
      (let [status (inst/status server-id)]
        (cond
          (= :running status) :ready
          (and seen-starting? (#{:offline :stopping} status)) :stopped
          (> (System/currentTimeMillis) deadline) :timeout
          :else (do (Thread/sleep (long hold-poll-ms))
                    (recur (or seen-starting? (= :starting status)))))))))

(defn- hold!
  "Keep CLIENT waiting until the server is ready, then forward it, replaying the
  handshake and login packets in ALREADY-READ. Disconnects the player with a message
  if the server doesn't come up in time."
  [^Socket client server player ^bytes already-read]
  (let [out (.getOutputStream client)
        name (servers/display-name server)]
    (log/info "Proxy: holding" player "until" (:name server) "is ready")
    (case (await-ready (:id server))
      :ready (if-let [port (:mc_port (servers/by-id (:id server)))]
               (forward! client already-read port)
               (proto/write! out (proto/login-disconnect-bytes "The server could not be reached.")))
      :stopped (proto/write! out (proto/login-disconnect-bytes (str name " stopped before it was ready.")))
      :timeout (proto/write! out (proto/login-disconnect-bytes
                                  (str name " is still starting. Reconnect in a moment!"))))))

(defn handle-client
  [^Socket client server-id]
  (try
    (.setSoTimeout client handshake-timeout-ms)
    (.setTcpNoDelay client true)
    (let [in (.getInputStream client)
          out (.getOutputStream client)
          server (servers/by-id server-id)]
      (when server
        (if (and (inst/ready? server-id) (:mc_port server))
          ;; Peek the first byte so legacy (pre-1.7) pings are forwarded untouched too.
          (let [first-byte (.read in)]
            (when-not (neg? first-byte)
              (forward! client (byte-array [(unchecked-byte first-byte)]) (:mc_port server))))
          (let [packet (proto/read-packet in)
                {:keys [protocol next-state]} (proto/parse-handshake packet)]
            (case (int next-state)
              1 (do (proto/read-packet in)    ; status request
                    (proto/write! out (proto/status-response-bytes protocol
                                                                   (status-motd server (inst/status server-id))
                                                                   "Block Shipper"))
                    (let [ping (proto/read-packet in)]
                      (proto/write! out (:raw ping))))
              (let [login (proto/read-packet in)
                    player (or (proto/read-login-name login) "unknown")
                    {:keys [hold kick]} (login-action server player)]
                (if hold
                  (hold! client server player (byte-array (concat (:raw packet) (:raw login))))
                  (proto/write! out (proto/login-disconnect-bytes kick)))))))))
    (catch SocketTimeoutException _ nil)
    (catch java.io.EOFException _ nil)
    (catch Exception e
      (log/debug "Proxy connection error:" (.getMessage e)))
    (finally
      (close-quietly client))))

(defn- accept-loop
  [^ServerSocket ss server-id]
  (loop []
    (when-let [client (try (.accept ss) (catch SocketException _ nil))]
      (future (handle-client client server-id))
      (recur))))

(defn- open-listener!
  [server]
  (let [port (:exposed_port server)
        bind (or (:proxy-bind-address (s/get-settings)) "0.0.0.0")]
    (try
      (let [ss (ServerSocket. (int port) 50 (InetAddress/getByName bind))]
        (doto (Thread. ^Runnable (fn [] (accept-loop ss (:id server))) (str "proxy-" (:name server)))
          (.setDaemon true)
          (.start))
        (log/info "Proxy listening for" (:name server) "on" (str bind ":" port))
        {:port port :socket ss})
      (catch Exception e
        (log/error "Proxy could not listen on port" port "for" (:name server) ":" (.getMessage e))
        {:port port :error (str "Could not listen on port " port ": " (.getMessage e))}))))

(defn sync-listeners!
  "Open listeners for every non-disabled server and close the ones no longer needed."
  []
  (locking listeners
    (let [wanted (into {} (for [server (servers/all-servers)
                                :when (and (:exposed_port server) (not= "disabled" (:host_mode server)))]
                            [(:id server) server]))]
      (doseq [[id {:keys [port socket]}] @listeners
              :when (or (not (contains? wanted id))
                        (not= port (:exposed_port (wanted id)))
                        (nil? socket))]
        (close-quietly socket)
        (swap! listeners dissoc id))
      (doseq [[id server] wanted
              :when (not (contains? @listeners id))]
        (swap! listeners assoc id (open-listener! server))))))

(defn stop-all!
  []
  (locking listeners
    (doseq [[_ {:keys [socket]}] @listeners]
      (close-quietly socket))
    (reset! listeners {})))

(defn install-hooks!
  []
  (servers/add-change-hook! ::listeners (fn [_] (sync-listeners!))))
