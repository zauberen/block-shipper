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

(ns com.blockshipper.minecraft.protocol
  "Just enough of the Minecraft Java protocol for the proxy: the handshake, the
  status (server list ping) exchange and the login-state disconnect packet."
  (:require [clojure.data.json :as json])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream DataInputStream EOFException InputStream OutputStream]
           [java.nio.charset StandardCharsets]))

(def max-packet-length (* 64 1024))

(defn read-varint
  "Read a VarInt from IN. Throws EOFException at end of stream."
  [^InputStream in]
  (loop [value 0 shift 0]
    (when (>= shift 35)
      (throw (ex-info "VarInt too long" {})))
    (let [b (.read in)]
      (when (neg? b) (throw (EOFException.)))
      (let [value (bit-or value (bit-shift-left (bit-and b 0x7f) shift))]
        (if (zero? (bit-and b 0x80))
          (unchecked-int value)
          (recur value (+ shift 7)))))))

(defn varint-bytes
  [n]
  (let [out (ByteArrayOutputStream.)]
    (loop [v (bit-and (long n) 0xffffffff)]
      (if (zero? (bit-and v (bit-not 0x7f)))
        (.write out (int v))
        (do (.write out (int (bit-or (bit-and v 0x7f) 0x80)))
            (recur (unsigned-bit-shift-right v 7)))))
    (.toByteArray out)))

(defn string-bytes
  [^String s]
  (let [b (.getBytes s StandardCharsets/UTF_8)]
    (byte-array (concat (varint-bytes (alength b)) b))))

(defn read-string*
  [^InputStream in]
  (let [n (read-varint in)]
    (when (or (neg? n) (> n 32767)) (throw (ex-info "Bad string length" {:length n})))
    (let [buf (byte-array n)]
      (.readFully (DataInputStream. in) buf)
      (String. buf StandardCharsets/UTF_8))))

(defn read-packet
  "Read one uncompressed packet. Returns {:id packet-id :body InputStream-of-the-rest
  :raw bytes-including-the-length-prefix}."
  [^InputStream in]
  (let [length (read-varint in)]
    (when (or (<= length 0) (> length max-packet-length))
      (throw (ex-info "Bad packet length" {:length length})))
    (let [data (byte-array length)]
      (.readFully (DataInputStream. in) data)
      (let [body (ByteArrayInputStream. data)
            id (read-varint body)]
        {:id id
         :body body
         :raw (byte-array (concat (varint-bytes length) data))}))))

(defn packet-bytes
  "Frame packet ID with DATA (a byte array) as an uncompressed packet."
  [id ^bytes data]
  (let [payload (byte-array (concat (varint-bytes id) data))]
    (byte-array (concat (varint-bytes (alength payload)) payload))))

(defn parse-handshake
  "Parse a handshake packet (as returned by read-packet)."
  [{:keys [id body]}]
  (when-not (zero? id)
    (throw (ex-info "Expected a handshake" {:id id})))
  (let [protocol (read-varint body)
        address (read-string* body)
        port (bit-or (bit-shift-left (.read ^InputStream body) 8) (.read ^InputStream body))
        next-state (read-varint body)]
    {:protocol protocol :address address :port port :next-state next-state}))

(defn handshake-bytes
  "Encode a handshake packet (used by tests and clients)."
  [protocol address port next-state]
  (packet-bytes 0 (byte-array (concat (varint-bytes protocol)
                                      (string-bytes address)
                                      [(unchecked-byte (bit-shift-right port 8)) (unchecked-byte port)]
                                      (varint-bytes next-state)))))

(defn status-response-bytes
  "The status response for the server list: MOTD text and a version that matches
  the client's PROTOCOL so it doesn't show as incompatible."
  [protocol motd version-name]
  (packet-bytes 0 (string-bytes (json/write-str {"version" {"name" version-name "protocol" protocol}
                                                 "players" {"max" 0 "online" 0}
                                                 "description" {"text" motd}}))))

(defn login-disconnect-bytes
  "Disconnect a client in the login state with MESSAGE."
  [message]
  (packet-bytes 0 (string-bytes (json/write-str {"text" message}))))

(defn read-login-name
  "The player name from a Login Start packet."
  [{:keys [id body]}]
  (when (zero? id)
    (read-string* body)))

(defn write!
  [^OutputStream out ^bytes data]
  (.write out data)
  (.flush out))
