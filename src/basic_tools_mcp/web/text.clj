(ns basic-tools-mcp.web.text
  "CPPB Promote — pure HTML→text transformation.

   No IO, no protocols, no state. html->text takes a string, returns a
   string. Lossy but safe for LLM consumption when a high-fidelity
   Markdown extractor isn't available."
  (:require [clojure.string :as str]))

(def ^:private html-entity->char
  {"&amp;"    "&"
   "&lt;"     "<"
   "&gt;"     ">"
   "&quot;"   "\""
   "&apos;"   "'"
   "&#39;"    "'"
   "&nbsp;"   " "
   "&mdash;"  "—"
   "&ndash;"  "–"
   "&hellip;" "…"
   "&copy;"   "©"
   "&reg;"    "®"})

(defn- decode-entities
  [^String s]
  (reduce-kv str/replace s html-entity->char))

(defn- drop-non-content-blocks
  [^String html]
  (-> html
      (str/replace #"(?is)<script[^>]*>.*?</script>" "")
      (str/replace #"(?is)<style[^>]*>.*?</style>"   "")
      (str/replace #"(?is)<!--.*?-->"                "")))

(defn- block-tags->newline
  [^String html]
  (str/replace html
               #"(?i)</?(p|br|div|li|tr|h[1-6])[^>]*>"
               "\n"))

(defn- drop-remaining-tags
  [^String html]
  (str/replace html #"<[^>]+>" ""))

(defn- collapse-whitespace
  [^String s]
  (-> s
      (str/replace #"[ \t]+" " ")
      (str/replace #"\n{3,}" "\n\n")
      str/trim))

(defn html->text
  "Strip HTML to plain text. Composition of pure transforms."
  [^String html]
  (-> html
      drop-non-content-blocks
      block-tags->newline
      drop-remaining-tags
      decode-entities
      collapse-whitespace))
