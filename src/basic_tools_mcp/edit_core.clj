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

(def ^:private tool-call-fragment-pattern
  "Regex matching LLM tool-call XML markup that should never reach a source file.
   Matches partial / streaming artifacts like `<invoke>`, `<parameter>`, `<new-body>`,
   `<function_calls>`, and `<...>` tags. The detector is intentionally narrow
   so legitimate Clojure code (e.g. `<` as a comparator, or `<foo>` as a Hiccup
   placeholder string) is not flagged."
  #"<(invoke|parameter|new-body|function_calls|antml:[A-Za-z_][A-Za-z0-9_]*)\b")

(defn detect-tool-call-fragment
  "Pure detector: scans `s` for leaked LLM tool-call XML markup.
   Returns the first matched tag string (e.g. \"<invoke\") or nil when clean.
   Total: never throws. Idempotent: deterministic on input."
  [s]
  (when (string? s)
    (when-let [m (re-find tool-call-fragment-pattern s)]
      ;; re-find returns [whole-match group1] for grouped patterns
      (if (vector? m) (first m) m))))

(defn find-all-tool-call-fragments
  "Pure detector: returns every distinct tag string matched in `s`, preserving
   first-seen order. Returns an empty vector for clean / non-string input.
   Total: never throws. Idempotent on input."
  [s]
  (if-not (string? s)
    []
    (let [matcher (re-matcher tool-call-fragment-pattern s)]
      (loop [seen #{} acc []]
        (if-not (.find matcher)
          acc
          (let [tag (.group matcher 0)]
            (if (contains? seen tag)
              (recur seen acc)
              (recur (conj seen tag) (conj acc tag)))))))))

(defn tool-call-fragment-error
  "Build the typed Result error for leaked tool-call XML markup.

   Shape (from carto-tool-unification-2026-05-11 plan §51-80):
     {:error      :tool-call-fragment-detected
      :tags-found [...]      ;; every distinct tag matched, ordered
      :message    \"...\"}    ;; human-readable

   Param `where` is a short scope tag (e.g. \"new_string\" / \"content\")
   surfaced in the message so the LLM knows which payload field tripped."
  [where tags]
  (r/err :tool-call-fragment-detected
         {:message    (str where " contains LLM tool-call markup "
                           (pr-str (vec tags))
                           "; likely a streaming artifact. Retry without the fragment.")
          :tags-found (vec tags)}))

(defn apply-edit
  "Pure: apply a single Edit to `content`. Returns Result<string>.

   Errors:
     :edit/not-found                — old-string did not appear in content
     :edit/ambiguous                — old-string appeared more than once and replace_all not set
     :edit/no-change                — content unchanged after replacement (old == new)
     :tool-call-fragment-detected   — new-string contains leaked LLM tool-call XML markup
                                      (`<invoke>`, `<parameter>`, `<new-body>`, etc.) — most
                                      likely a streaming artifact; caller should retry.
                                      Payload carries `:tags-found [...]` (vector of every
                                      distinct tag matched, in first-seen order).

   Trailing-newline edge case (mirrors claude-code-haha): when new-string is
   empty and old-string lacks a trailing newline, prefer matching `old + \\n`
   so the line is fully removed."
  [{:keys [content old-string new-string replace-all?]}]
  (let [tags (find-all-tool-call-fragments new-string)]
    (if (seq tags)
      (tool-call-fragment-error "new_string" tags)
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
            (r/ok updated))))))))