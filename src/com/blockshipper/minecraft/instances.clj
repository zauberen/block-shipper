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

(ns com.blockshipper.minecraft.instances
  "Runs minecraft servers as child processes of Block Shipper.

  Each running server has an entry in `instances` keyed by server id. A reader thread
  follows the process output, keeps the last lines in memory for the console, and turns
  log lines into player and state events."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.blockshipper.global-settings :as gs]
            [com.blockshipper.minecraft.log-parser :as parser]
            [com.blockshipper.minecraft.provider :as provider]
            [com.blockshipper.minecraft.setup :as setup]
            [com.blockshipper.players :as players]
            [com.blockshipper.servers :as servers]
            [com.blockshipper.uptime :as uptime]
            [com.blockshipper.util :as u])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent TimeUnit]))

(def log-buffer-size 1000)
(def stop-timeout-ms 60000)

;; server-id -> {:process :writer :state :players :log :started-at :started-by :uptime-id
;;               :last-activity :stop-reason :uuids}
(defonce instances (atom {}))

;; key -> (fn [server-id event]); events are {:type :log :line l} and {:type :state :state s}
(defonce listeners (atom {}))

;; key -> (fn [server reason]) run on a background thread after a server process exits
(defonce exit-hooks (atom {}))

;; Servers being backed up or updated; they can't be started meanwhile.
(defonce busy (atom #{}))

(defn call-while-busy
  "Run F with SERVER-ID marked busy. Throws if it is already busy."
  [server-id f]
  (let [[old _] (swap-vals! busy conj server-id)]
    (when (contains? old server-id)
      (throw (ex-info "busy" {:user-message "The server is busy with a backup or update. Try again shortly."})))
    (try (f)
         (finally (swap! busy disj server-id)))))

(defn busy? [server-id] (contains? @busy server-id))

(defn add-listener! [k f] (swap! listeners assoc k f))
(defn remove-listener! [k] (swap! listeners dissoc k))
(defn add-exit-hook! [k f] (swap! exit-hooks assoc k f))

(defn- notify!
  [server-id event]
  (doseq [[_ f] @listeners]
    (try (f server-id event)
         (catch Exception e (log/warn e "Instance listener failed")))))

(defn instance [server-id] (get @instances server-id))

(defn status
  "One of :offline, :starting, :running or :stopping."
  [server-id]
  (or (:state (instance server-id)) :offline))

(defn running?
  "True while the server process is alive (in any state other than offline)."
  [server-id]
  (some? (instance server-id)))

(defn ready?
  "True once the server finished starting and accepts players."
  [server-id]
  (= :running (status server-id)))

(defn online-players
  [server-id]
  (or (:players (instance server-id)) #{}))

(defn log-lines
  "The buffered console output for SERVER-ID."
  [server-id]
  (or (:log (instance server-id)) []))

(defn running-ids
  []
  (set (keys @instances)))

(defn- set-state!
  [server-id state]
  (swap! instances (fn [m] (if (contains? m server-id) (assoc-in m [server-id :state] state) m)))
  (notify! server-id {:type :state :state state}))

(defn- append-log
  [lines line]
  (let [lines (conj lines line)]
    (if (> (count lines) log-buffer-size)
      (subvec lines (- (count lines) log-buffer-size))
      lines)))

(defn- record-line!
  [server-id line]
  (swap! instances (fn [m] (if (contains? m server-id) (update-in m [server-id :log] append-log line) m)))
  (notify! server-id {:type :log :line line}))

(defn handle-event!
  "Apply a parsed log EVENT to SERVER-ID's state and the database."
  [server-id event]
  (case (:type event)
    :ready (do (swap! instances update server-id assoc :last-activity (u/now-ms))
               (set-state! server-id :running))
    :uuid (swap! instances assoc-in [server-id :uuids (:player event)] (:uuid event))
    :join (let [player (:player event)
                uuid (get-in @instances [server-id :uuids player])]
            (swap! instances update-in [server-id :players] (fnil conj #{}) player)
            (players/open-session! server-id player uuid (u/now-ms))
            (notify! server-id {:type :players}))
    :leave (let [player (:player event)]
             (swap! instances update server-id
                    (fn [inst] (-> inst
                                   (update :players disj player)
                                   (assoc :last-activity (u/now-ms)))))
             (players/close-session! server-id player)
             (notify! server-id {:type :players}))
    :stopping (when (= :running (status server-id))
                (set-state! server-id :stopping))
    nil))

(defn- handle-line!
  [server-id line]
  (record-line! server-id line)
  (when-let [event (parser/parse-line line)]
    (try (handle-event! server-id event)
         (catch Exception e (log/error e "Failed handling event" event)))))

(defn- pid-file [server] (io/file (servers/server-dir server) ".blockshipper.pid"))

(defn- on-exit!
  [server ^Process proc]
  (let [server-id (:id server)
        inst (instance server-id)
        code (.exitValue proc)
        reason (or (:stop-reason inst)
                   (if (zero? code) "stopped from console" (str "crashed (exit code " code ")")))]
    (log/info "Server" (:name server) "exited:" reason)
    (when-let [id (:uptime-id inst)]
      (uptime/close! id reason))
    (players/close-all-sessions! server-id)
    (io/delete-file (pid-file server) true)
    (swap! instances dissoc server-id)
    (notify! server-id {:type :state :state :offline})
    (doseq [[k hook] @exit-hooks]
      (try (hook (or (servers/by-id server-id) server) reason)
           (catch Exception e (log/error e "Exit hook" k "failed for" (:name server)))))))

(defn- reader-loop
  [server ^Process proc]
  (let [server-id (:id server)]
    (try
      (with-open [^BufferedReader rdr (BufferedReader.
                                       (InputStreamReader. (.getInputStream proc) StandardCharsets/UTF_8))]
        (loop []
          (when-let [line (.readLine rdr)]
            (handle-line! server-id line)
            (recur))))
      (catch Exception e
        (log/warn "Output reader for" (:name server) "stopped:" (.getMessage e))))
    (.waitFor proc)
    (on-exit! server proc)))

(defn kill-orphan!
  "Stop a process left running by a previous Block Shipper that was killed without shutting down."
  [server]
  (let [f (pid-file server)]
    (when (.exists f)
      (when-let [pid (some-> (slurp f) str/trim parse-long)]
        (let [handle (.orElse (java.lang.ProcessHandle/of pid) nil)]
          (when (and handle (.isAlive handle)
                     (some-> (.orElse (.commandLine (.info handle)) "") (str/includes? "server.jar")))
            (log/warn "Stopping orphaned server process" pid "for" (:name server))
            (.destroy handle)
            (try (.get (.onExit handle) 60 TimeUnit/SECONDS)
                 (catch Exception _ (.destroyForcibly handle))))))
      (io/delete-file f true))))

(defn- launch!
  [server started-by]
  (let [server-id (:id server)
        dir (servers/server-dir server)
        command (provider/launch-command server)]
    (when-not (.exists (io/file dir "server.jar"))
      (throw (ex-info "server.jar missing" {:user-message "This server is not installed yet."})))
    (kill-orphan! server)
    (setup/apply-managed-properties! server)
    (let [pb (doto (ProcessBuilder. ^java.util.List command)
               (.directory dir)
               (.redirectErrorStream true))
          proc (.start pb)
          writer (OutputStreamWriter. (.getOutputStream proc) StandardCharsets/UTF_8)
          now (u/now-ms)]
      (spit (pid-file server) (str (.pid proc)))
      (swap! instances assoc server-id
             {:process proc
              :writer writer
              :state :starting
              :players #{}
              :uuids {}
              :log []
              :started-at now
              :last-activity now
              :started-by started-by
              :uptime-id (uptime/open! server-id started-by now)})
      (notify! server-id {:type :state :state :starting})
      (log/info "Started server" (:name server) "for" started-by)
      (doto (Thread. ^Runnable (fn [] (reader-loop server proc)) (str "mc-" (:name server)))
        (.setDaemon true)
        (.start))
      proc)))

(defn send-command!
  "Write COMMAND to SERVER-ID's console. Returns false when the server is not running.
  Line breaks become spaces so one call can only ever run one command."
  [server-id command]
  (if-let [^OutputStreamWriter w (:writer (instance server-id))]
    (let [command (str/replace (str command) #"[\r\n]+" " ")]
      (try
        (locking w
          (.write w (str command "\n"))
          (.flush w))
        (record-line! server-id (str "> " command))
        true
        (catch Exception e
          (log/warn "Could not send command to" server-id ":" (.getMessage e))
          false)))
    false))

(defn stop!
  "Ask SERVER-ID to stop gracefully, forcing it after a timeout.
  Returns a future that completes once the process has exited."
  [server-id reason]
  (if-let [inst (instance server-id)]
    (let [^Process proc (:process inst)]
      (swap! instances update server-id assoc :stop-reason reason)
      (set-state! server-id :stopping)
      (send-command! server-id "stop")
      (future
        (when-not (.waitFor proc stop-timeout-ms TimeUnit/MILLISECONDS)
          (log/warn "Server" server-id "did not stop in time, terminating")
          (.destroy proc)
          (when-not (.waitFor proc 15 TimeUnit/SECONDS)
            (.destroyForcibly proc)
            (.waitFor proc)))
        ;; Wait for the reader thread to finish its exit bookkeeping.
        (loop [n 0]
          (when (and (running? server-id) (< n 100))
            (Thread/sleep 50)
            (recur (inc n))))
        true))
    (future false)))

(defn stop-all!
  "Stop every running server and wait for them to exit. Used on shutdown."
  [reason]
  (doseq [f (mapv #(stop! % reason) (running-ids))]
    (deref f (+ stop-timeout-ms 20000) nil)))

(defn pick-victim
  "Choose a running server to stop to make room: one with no players, preferring
  published servers, then the one idle the longest. RUNNING is a seq of
  {:id :host-mode :players :last-activity :state}."
  [running]
  (->> running
       (filter #(and (zero? (:players %)) (= :running (:state %))))
       (sort-by (juxt #(if (= "published" (:host-mode %)) 0 1) :last-activity))
       first))

(defn- running-summary
  []
  (for [[id inst] @instances]
    {:id id
     :host-mode (:host_mode (servers/by-id id))
     :players (count (:players inst))
     :last-activity (:last-activity inst)
     :state (:state inst)}))

(def ^:private start-lock (Object.))

(defn start!
  "Start SERVER (a mc_servers record) on behalf of STARTED-BY.

  Throws ex-info with :user-message when the server cannot be started. When the
  concurrent server limit is reached an idle server is stopped first; in that case
  the launch happens in the background and {:status :queued :stopping name} is
  returned. Otherwise returns {:status :started} or {:status :already-running}."
  [server started-by]
  (locking start-lock
    (let [server-id (:id server)
          limit (gs/max-running)]
      (cond
        (running? server-id)
        {:status :already-running}

        (= "disabled" (:host_mode server))
        (throw (ex-info "disabled" {:user-message "This server is disabled."}))

        (busy? server-id)
        (throw (ex-info "busy" {:user-message "The server is busy with a backup or update. Try again in a minute."}))

        (and limit (>= (count @instances) limit))
        (if-let [victim (pick-victim (running-summary))]
          (let [victim-server (servers/by-id (:id victim))
                stopped (stop! (:id victim) (str "stopped to make room for " (servers/display-name server)))]
            (future
              (try
                @stopped
                (locking start-lock
                  (when-not (running? server-id)
                    (launch! server started-by)))
                (catch Exception e
                  (log/error e "Failed to start" (:name server)))))
            {:status :queued :stopping (servers/display-name victim-server)})
          (throw (ex-info "capacity"
                          {:user-message (str "The maximum of " limit " running servers has been reached "
                                              "and every running server has players online.")})))

        :else
        (do (launch! server started-by)
            {:status :started})))))
