(ns basic-tools-mcp.core
  "Bridge to clojure-mcp-light functions.

   On babashka: requires clojure-mcp-light directly.
   On JVM: uses requiring-resolve with graceful degradation."
  (:require [clojure-mcp-light.delimiter-repair :as dr]
            [basic-tools-mcp.log :as log]))

;; =============================================================================
;; Lazy Resolution (nREPL deps need babashka.fs)
;; =============================================================================

(defn- try-resolve [sym]
  (try (requiring-resolve sym) (catch Exception _ nil)))

;; =============================================================================
;; Delimiter Functions (always available — pure Clojure)
;; =============================================================================

(defn delimiter-error?
  "Check if code has delimiter errors. Returns boolean."
  [code]
  (boolean (dr/delimiter-error? code)))

(defn actual-delimiter-error?
  "Non-signaling delimiter error check. Returns boolean."
  [code]
  (boolean (dr/actual-delimiter-error? code)))

(defn repair-delimiters
  "Repair delimiter errors. Returns {:success bool :text string :error string}."
  [code]
  (dr/repair-delimiters code))

(defn fix-delimiters
  "Fix delimiters — returns repaired string or original if no errors."
  [code]
  (dr/fix-delimiters code))

;; =============================================================================
;; Formatting (requires cljfmt)
;; =============================================================================

(defn format-code
  "Format Clojure code with cljfmt. Returns formatted string."
  [code]
  (if-let [reformat (try-resolve 'cljfmt.core/reformat-string)]
    (reformat code)
    (do (log/warn "cljfmt not available")
        code)))

;; =============================================================================
;; nREPL Functions (require babashka.fs — bb only)
;; =============================================================================

(defn eval-code
  "Evaluate code via nREPL. Returns captured stdout string.
   Requires babashka context for full functionality."
  [{:keys [code port host timeout]}]
  (if-let [eval-fn (try-resolve 'clojure-mcp-light.nrepl-eval/eval-expr-with-timeout)]
    (with-out-str
      (eval-fn {:host (or host "localhost")
                :port port
                :expr code
                :timeout-ms (or timeout 120000)}))
    (throw (ex-info "nREPL eval not available (requires babashka context)" {}))))

(defn discover-ports
  "Discover nREPL servers. Returns vector of port info maps.
   Requires babashka context for full functionality."
  []
  (if-let [discover-fn (try-resolve 'clojure-mcp-light.nrepl-eval/discover-nrepl-ports)]
    (vec (discover-fn))
    (throw (ex-info "nREPL discover not available (requires babashka context)" {}))))
