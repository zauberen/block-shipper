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

(ns com.blockshipper.response
  (:require [hiccup2.core :as h]))

(defn respond-generic
  "Generic response with the given BODY and CONTENT-TYPE."
  [body content-type]
  {:status 200
   :body body
   :headers {"Content-Type" content-type}})

(defn respond
  "Make a basic html response with the given HTML string."
  [html]
  {:status 200
   :body html
   :headers {"Content-Type" "text/html; charset=UTF-8"}})

(defn h-respond
  "Make a basic hiccup html response with the given HIC-HTML."
  [hic-html]
  (respond (str (h/html hic-html))))
