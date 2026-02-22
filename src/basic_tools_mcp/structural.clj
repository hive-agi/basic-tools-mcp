(ns basic-tools-mcp.structural
  "Pure structural editing operations via rewrite-clj.

   No IO — operates on source strings only.
   Domain model for the structural editing bounded context.

   Value objects: source strings, templates, line numbers.
   Domain predicates: balanced?, clojure-source-file?
   Domain operations: locate-top-level-form, wrap-form-str, wrap-in-source."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [rewrite-clj.zip :as z]))

;; =============================================================================
;; Domain Value Objects
;; =============================================================================

(def clojure-extensions
  "File extensions that denote Clojure source files.
   Value object — immutable set of recognized extensions."
  #{".clj" ".cljs" ".cljc" ".edn"})

;; =============================================================================
;; Domain Predicates
;; =============================================================================

(defn clojure-source-file?
  "True if file_path has a Clojure source extension."
  [file_path]
  (boolean (some #(str/ends-with? (str file_path) %) clojure-extensions)))

(defn balanced?
  "Check if source has balanced delimiters via rewrite-clj parse.
   Returns true if all parens/brackets/braces are properly matched."
  [source]
  (boolean (r/guard Exception nil (z/of-string source))))

;; =============================================================================
;; Form Location
;; =============================================================================

(defn locate-top-level-form
  "Find the top-level form containing target-line (1-based).
   Returns {:zloc z :start-line int :end-line int} or nil."
  [source target-line]
  (let [zloc (z/of-string source {:track-position? true})]
    (loop [loc zloc]
      (when (and loc (not (z/end? loc)))
        (let [[line _] (z/position loc)
              s (z/string loc)
              end-line (+ line (count (filter #(= \newline %) s)))]
          (if (<= line target-line end-line)
            {:zloc loc :start-line line :end-line end-line}
            (recur (z/right loc))))))))

;; =============================================================================
;; Form Wrapping
;; =============================================================================

(defn wrap-form-str
  "Wrap a form string with a template containing %s placeholder.
   Returns Result<string>."
  [form-str template]
  (let [wrapped (str/replace template "%s" form-str)]
    (r/try-effect* :structural/invalid-template
                   (z/root-string (z/of-string wrapped)))))

(defn wrap-in-source
  "Wrap a top-level form at target-line within source using template.
   Template uses %s as placeholder for the original form.
   Returns Result<new-source>."
  [source target-line template]
  (r/guard Exception
           (r/err :structural/parse-failed {:message "Source has invalid syntax"})
           (if-let [{:keys [zloc]} (locate-top-level-form source target-line)]
             (r/let-ok [wrapped (wrap-form-str (z/string zloc) template)]
                       (r/try-effect* :structural/insert-failed
                                      (z/root-string (z/replace zloc (z/node (z/of-string wrapped))))))
             (r/err :structural/no-form
                    {:message (str "No top-level form found at line " target-line)}))))
