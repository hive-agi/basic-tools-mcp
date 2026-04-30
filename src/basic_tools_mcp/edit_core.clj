(ns basic-tools-mcp.edit-core
  "Pure string-edit primitives for the Edit tool.

   No IO. Functions take strings, return strings or Result.
   The IO sandwich lives in basic-tools-mcp.file-core/edit-file.

   Algorithm mirrors claude-code-haha/src/tools/FileEditTool/utils.ts
   with hive-dsl Result returns instead of throws."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]))

;; =============================================================================
;; Quote Normalization
;; =============================================================================
;; Models often emit straight quotes when the file uses curly typographic
;; quotes. Normalize both sides for matching, then preserve original style
;; in the replacement output.

(def ^:const left-single-curly  "‘")
(def ^:const right-single-curly "’")
(def ^:const left-double-curly  "“")
(def ^:const right-double-curly "”")

(defn normalize-quotes
  "Replace curly quotes with straight equivalents."
  [s]
  (-> s
      (str/replace left-single-curly  "'")
      (str/replace right-single-curly "'")
      (str/replace left-double-curly  "\"")
      (str/replace right-double-curly "\"")))

(defn- opening-context?
  [^String s ^long idx]
  (or (zero? idx)
      (let [prev (.charAt s (dec idx))]
        (or (= prev \space) (= prev \tab) (= prev \newline) (= prev \return)
            (= prev \() (= prev \[) (= prev \{)
            (= prev \u2014) (= prev \u2013)))))

(defn- apply-curly-double
  [^String s]
  (let [sb (StringBuilder.)]
    (dotimes [i (.length s)]
      (let [c (.charAt s i)]
        (.append sb (if (= c \")
                     (if (opening-context? s i) left-double-curly right-double-curly)
                     c))))
    (.toString sb)))

(defn- letter? [^Character c]
  (and c (Character/isLetter c)))

(defn- apply-curly-single
  [^String s]
  (let [sb  (StringBuilder.)
        len (.length s)]
    (dotimes [i len]
      (let [c (.charAt s i)]
        (if (= c \')
          (let [prev (when (pos? i) (.charAt s (dec i)))
                next (when (< (inc i) len) (.charAt s (inc i)))]
            (cond
              (and (letter? prev) (letter? next))
              (.append sb right-single-curly)

              (opening-context? s i)
              (.append sb left-single-curly)

              :else
              (.append sb right-single-curly)))
          (.append sb c))))
    (.toString sb)))

(defn preserve-quote-style
  "If `actual-old` differed from `old` only via quote normalization, apply
   the file's curly style to `new-string` so typography survives the edit."
  [old actual-old new-string]
  (if (= old actual-old)
    new-string
    (let [has-double? (or (str/includes? actual-old left-double-curly)
                          (str/includes? actual-old right-double-curly))
          has-single? (or (str/includes? actual-old left-single-curly)
                          (str/includes? actual-old right-single-curly))]
      (cond-> new-string
        has-double? apply-curly-double
        has-single? apply-curly-single))))

;; =============================================================================
;; Match Resolution
;; =============================================================================

(defn find-actual-string
  "Return the substring in `content` that matches `search`, or nil.
   Tries exact match first, then quote-normalized fallback."
  [content search]
  (cond
    (str/includes? content search)
    search

    :else
    (let [norm-c (normalize-quotes content)
          norm-s (normalize-quotes search)
          idx    (.indexOf ^String norm-c ^String norm-s)]
      (when (>= idx 0)
        (subs content idx (+ idx (.length ^String search)))))))

(defn- count-occurrences
  [^String content ^String search]
  (loop [from 0 n 0]
    (let [i (.indexOf content search from)]
      (if (neg? i)
        n
        (recur (+ i (.length search)) (inc n))))))

;; =============================================================================
;; Apply Edit
;; =============================================================================

(defn apply-edit
  "Pure: apply a single Edit to `content`. Returns Result<string>.

   Errors:
     :edit/not-found    — old-string did not appear in content
     :edit/ambiguous    — old-string appeared more than once and replace_all not set
     :edit/no-change    — content unchanged after replacement (old == new)

   Trailing-newline edge case (mirrors claude-code-haha): when new-string is
   empty and old-string lacks a trailing newline, prefer matching `old + \\n`
   so the line is fully removed."
  [{:keys [content old-string new-string replace-all?]}]
  (let [actual (find-actual-string content old-string)]
    (cond
      (nil? actual)
      (r/err :edit/not-found
             {:message "old_string not found in file (exact + quote-normalized match both failed)"})

      (and (not replace-all?)
           (> (count-occurrences content actual) 1))
      (r/err :edit/ambiguous
             {:message "old_string appears multiple times; pass replace_all=true or expand context"
              :occurrences (count-occurrences content actual)})

      :else
      (let [new-applied (preserve-quote-style old-string actual new-string)
            ;; Empty-replacement trailing-newline trick
            [match-str apply-str] (if (and (= "" new-string)
                                           (not (str/ends-with? actual "\n"))
                                           (str/includes? content (str actual "\n")))
                                    [(str actual "\n") ""]
                                    [actual new-applied])
            updated (if replace-all?
                      (str/replace content match-str apply-str)
                      (str/replace-first content match-str apply-str))]
        (if (= updated content)
          (r/err :edit/no-change
                 {:message "Replacement left content identical to original"})
          (r/ok updated))))))
