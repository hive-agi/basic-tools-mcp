(ns basic-tools-mcp.core
  "Bridge to clojure-mcp-light functions.

   On babashka: clojure-mcp-light is on classpath (bb.edn git dep).
   On JVM: uses requiring-resolve with graceful degradation.
   All external deps are lazy-resolved — this ns loads without them."
  (:require [basic-tools-mcp.log :as log]
            [hive-dsl.result :as r]))

;; =============================================================================
;; Lazy Resolution
;; =============================================================================

(defn- resolve-api!
  "Resolve a symbol via requiring-resolve. Returns Result<var>."
  [sym]
  (if-let [v (r/guard Exception nil (requiring-resolve sym))]
    (r/ok v)
    (r/err :ns/dependency-unavailable {:symbol sym})))

;; =============================================================================
;; Delimiter Functions (require clojure-mcp-light — available in bb, optional on JVM)
;; =============================================================================

(defn delimiter-error?
  "Check if code has delimiter errors. Returns boolean.
   Falls back to false if clojure-mcp-light unavailable."
  [code]
  (if-let [f (:ok (resolve-api! 'clojure-mcp-light.delimiter-repair/delimiter-error?))]
    (boolean (f code))
    (do (log/warn "clojure-mcp-light not available, skipping delimiter check")
        false)))

(defn actual-delimiter-error?
  "Non-signaling delimiter error check. Returns boolean."
  [code]
  (if-let [f (:ok (resolve-api! 'clojure-mcp-light.delimiter-repair/actual-delimiter-error?))]
    (boolean (f code))
    false))

(defn repair-delimiters
  "Repair delimiter errors. Returns {:success bool :text string :error string}."
  [code]
  (if-let [f (:ok (resolve-api! 'clojure-mcp-light.delimiter-repair/repair-delimiters))]
    (f code)
    {:success false :error "clojure-mcp-light not available"}))

(defn fix-delimiters
  "Fix delimiters — returns repaired string or original if no errors."
  [code]
  (if-let [f (:ok (resolve-api! 'clojure-mcp-light.delimiter-repair/fix-delimiters))]
    (f code)
    code))

;; =============================================================================
;; Formatting (requires cljfmt)
;; =============================================================================

(defn format-code
  "Format Clojure code with cljfmt. Returns Result<string>."
  [code]
  (r/let-ok [reformat (resolve-api! 'cljfmt.core/reformat-string)]
            (r/try-effect* :format/failed (reformat code))))

;; =============================================================================
;; nREPL Functions (require babashka context)
;; =============================================================================

(defn eval-code
  "Evaluate code via nREPL. Returns Result<string>.
   Requires babashka context for full functionality."
  [{:keys [code port host timeout]}]
  (r/let-ok [eval-fn (resolve-api! 'clojure-mcp-light.nrepl-eval/eval-expr-with-timeout)]
            (r/try-effect* :nrepl/eval-failed
                           (with-out-str
                             (eval-fn {:host (or host "localhost")
                                       :port port
                                       :expr code
                                       :timeout-ms (or timeout 120000)})))))

(defn discover-ports
  "Discover nREPL servers. Returns Result<vector>.
   Requires babashka context for full functionality."
  []
  (r/let-ok [discover-fn (resolve-api! 'clojure-mcp-light.nrepl-eval/discover-nrepl-ports)]
            (r/try-effect* :nrepl/discover-failed (vec (discover-fn)))))
