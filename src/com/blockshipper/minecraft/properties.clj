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

(ns com.blockshipper.minecraft.properties
  "Reads and writes server.properties while keeping comments and key order."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- unescape
  [s]
  (let [sb (StringBuilder.)
        n (count s)]
    (loop [i 0]
      (if (>= i n)
        (str sb)
        (let [c (.charAt s i)]
          (if (and (= c \\) (< (inc i) n))
            (let [nc (.charAt s (inc i))]
              (case nc
                \t (do (.append sb \tab) (recur (+ i 2)))
                \n (do (.append sb \newline) (recur (+ i 2)))
                \r (do (.append sb \return) (recur (+ i 2)))
                \f (do (.append sb \formfeed) (recur (+ i 2)))
                \u (if (<= (+ i 6) n)
                     (do (.append sb (char (Integer/parseInt (subs s (+ i 2) (+ i 6)) 16)))
                         (recur (+ i 6)))
                     (do (.append sb nc) (recur (+ i 2))))
                (do (.append sb nc) (recur (+ i 2)))))
            (do (.append sb c) (recur (inc i)))))))))

(defn- escape
  [s key?]
  (let [sb (StringBuilder.)]
    (doseq [[i c] (map-indexed vector s)]
      (cond
        (= c \\) (.append sb "\\\\")
        (= c \newline) (.append sb "\\n")
        (= c \return) (.append sb "\\r")
        (= c \tab) (.append sb "\\t")
        (and key? (#{\= \: \space} c)) (.append sb (str "\\" c))
        (and (not key?) (zero? i) (= c \space)) (.append sb "\\ ")
        (and (zero? i) (#{\# \!} c)) (.append sb (str "\\" c))
        (> (int c) 126) (.append sb (format "\\u%04x" (int c)))
        :else (.append sb c)))
    (str sb)))

(defn- split-entry
  "Split a property line at the first unescaped = or :."
  [line]
  (let [n (count line)]
    (loop [i 0]
      (cond
        (>= i n) [line ""]
        (= \\ (.charAt line i)) (recur (+ i 2))
        (#{\= \:} (.charAt line i)) [(subs line 0 i) (subs line (inc i))]
        :else (recur (inc i))))))

(defn parse
  "Parse properties TEXT into a vector of entries:
  {:type :other :line l} for comments/blank lines and {:type :prop :key k :value v}."
  [text]
  (mapv (fn [line]
          (let [trimmed (str/triml line)]
            (if (or (str/blank? trimmed) (#{\# \!} (first trimmed)))
              {:type :other :line line}
              (let [[k v] (split-entry trimmed)]
                {:type :prop
                 :key (unescape (str/trim k))
                 :value (unescape (str/triml v))}))))
        (str/split-lines (or text ""))))

(defn render
  "Turn parsed ENTRIES back into text."
  [entries]
  (str (str/join "\n" (map (fn [e]
                             (if (= :prop (:type e))
                               (str (escape (:key e) true) "=" (escape (:value e) false))
                               (:line e)))
                           entries))
       "\n"))

(defn ->map
  "The properties in ENTRIES as a map of key to value."
  [entries]
  (into {} (comp (filter #(= :prop (:type %))) (map (juxt :key :value))) entries))

(defn assoc-props
  "Set the properties in M (string keys and values), keeping the order of existing keys
  and appending new keys at the end."
  [entries m]
  (let [m (into {} (map (fn [[k v]] [(name k) (str v)])) m)
        existing (set (keep :key entries))]
    (into (mapv (fn [e]
                  (if (and (= :prop (:type e)) (contains? m (:key e)))
                    (assoc e :value (get m (:key e)))
                    e))
                entries)
          (for [[k v] m :when (not (existing k))]
            {:type :prop :key k :value v}))))

(defn properties-file
  [server-dir]
  (io/file server-dir "server.properties"))

(defn read-props
  "Parsed entries of SERVER-DIR's server.properties (empty when it does not exist)."
  [server-dir]
  (let [f (properties-file server-dir)]
    (if (.exists f)
      (parse (slurp f :encoding "UTF-8"))
      [])))

(defn write-props!
  [server-dir entries]
  (spit (properties-file server-dir) (render entries) :encoding "UTF-8"))

(defn update-props!
  "Set the properties in M in SERVER-DIR's server.properties."
  [server-dir m]
  (write-props! server-dir (assoc-props (read-props server-dir) m)))
