(ns basic-tools-mcp.tools
  "MCP tool handlers for basic Clojure development tools.

   Exposes handle-clojure for hive-mcp IAddon integration
   and tool-def for MCP schema registration.

   Commands: check, repair, format, eval, discover, wrap
   File tools: read_file, file_write, glob_files, grep"
  (:require [basic-tools-mcp.core :as core]
            [basic-tools-mcp.file-core :as fc]
            [basic-tools-mcp.log :as log]
            [clojure.string :as str]
            [hive-dsl.result :as r]))

;; =============================================================================
;; Command Helpers — Anti-corruption layer (DRY source resolution)
;; =============================================================================

(defn- resolve-source
  "Resolve source text from :code or :file_path params.
   Returns Result<{:text string :source label}>.
   Anti-corruption layer: validates input at bounded context gate."
  [{:keys [code file_path]}]
  (cond
    code      (r/ok {:text code :source "inline"})
    file_path (r/try-effect* :io/read-failure
                             {:text (slurp file_path) :source file_path})
    :else     (r/err :input/missing {:message "Provide 'code' (string) or 'file_path'"})))

;; =============================================================================
;; Command Handlers
;; =============================================================================

(def ^:private command-handlers
  {"check"    (fn [params]
                (r/let-ok [{:keys [text source]} (resolve-source params)]
                          (r/ok {:has-error (core/delimiter-error? text)
                                 :source source})))

   "repair"   (fn [params]
                (r/let-ok [{:keys [text source]} (resolve-source params)]
                          (let [had-error (core/actual-delimiter-error? text)
                                result (core/repair-delimiters text)]
                            (if (:success result)
                              (do (when (and (not= source "inline") (:text result))
                                    (spit source (:text result)))
                                  (r/ok {:success true
                                         :text (:text result)
                                         :had-error had-error
                                         :source source}))
                              (r/err :repair/failed
                                     {:message (or (:error result) "Repair failed")
                                      :had-error had-error})))))

   "format"   (fn [params]
                (r/let-ok [{:keys [text source]} (resolve-source params)
                           formatted (core/format-code text)]
                          (when (not= source "inline") (spit source formatted))
                          (r/ok {:formatted formatted
                                 :changed (not= text formatted)
                                 :source source})))

   "eval"     (fn [{:keys [code port host timeout]}]
                (if (and code port)
                  (r/let-ok [output (core/eval-code {:code code :port port
                                                     :host host :timeout timeout})]
                            (r/ok {:output output
                                   :host (or host "localhost")
                                   :port port}))
                  (r/err :input/missing {:message "Requires 'code' and 'port'"})))

   "discover" (fn [_params]
                (r/let-ok [ports (core/discover-ports)]
                          (r/ok {:ports ports :count (count ports)})))

   "wrap"     (fn [{:keys [file_path line template]}]
                (cond
                  (nil? file_path) (r/err :input/missing {:message "file_path is required"})
                  (nil? line)      (r/err :input/missing {:message "line is required"})
                  (nil? template)  (r/err :input/missing {:message "template is required (use %s as placeholder)"})
                  (not (str/includes? template "%s"))
                  (r/err :input/invalid {:message "template must contain %s placeholder"})
                  :else
                  (r/let-ok [msg (fc/wrap-form {:file_path file_path
                                                :line line
                                                :template template})]
                            (r/ok {:success true :message msg}))))})

;; =============================================================================
;; MCP Interface (IAddon integration)
;; =============================================================================

(defn handle-clojure
  "MCP tool handler for clojure commands. Dispatches on :command key.
   Returns MCP-compatible response map with :content vector."
  [{:keys [command] :as params}]
  (if-let [handler (get command-handlers command)]
    (let [result (try
                   (handler params)
                   (catch Exception e
                     (log/error "clojure command failed:" command (ex-message e))
                     (r/err :command/exception {:command command
                                                :message (ex-message e)})))]
      (if (r/ok? result)
        {:content [{:type "text" :text (pr-str (:ok result))}]}
        {:content [{:type "text" :text (pr-str result)}]
         :isError true}))
    {:content [{:type "text" :text (pr-str (r/err :command/unknown
                                                  {:command   command
                                                   :available (sort (keys command-handlers))}))}]
     :isError true}))

(defn tool-def
  "MCP tool definition for the clojure tool."
  []
  {:name        "clojure"
   :description "Clojure dev tools: check (delimiter errors), repair (fix delimiters), format (cljfmt), eval (nREPL), discover (nREPL ports), wrap (structural edit)"
   :inputSchema {:type       "object"
                 :properties {:command   {:type "string"
                                          :enum (sort (keys command-handlers))}
                              :code      {:type        "string"
                                          :description "Clojure code string (for check/repair/format/eval)"}
                              :file_path {:type        "string"
                                          :description "Path to Clojure file (for check/repair/format)"}
                              :port      {:type        "number"
                                          :description "nREPL port (for eval)"}
                              :host      {:type        "string"
                                          :description "nREPL host (default: localhost)"}
                              :timeout   {:type        "number"
                                          :description "Timeout in ms (default: 120000)"}
                              :line      {:type        "integer"
                                          :description "1-based line number of the form to wrap (for wrap)"}
                              :template  {:type        "string"
                                          :description "Wrap template with %s placeholder for the original form (for wrap)"}}
                 :required   ["command"]}})

;; =============================================================================
;; File Tool Handlers (standalone tools, not supertool command dispatch)
;; =============================================================================

(defn- result->mcp
  "Convert Result ADT to MCP response format.
   (ok text) => {:content [{:type \"text\" :text text}]}
   (err cat) => {:content [{:type \"text\" :text msg}] :isError true}"
  [result]
  (if (r/ok? result)
    {:content [{:type "text" :text (:ok result)}]}
    {:content [{:type "text" :text (or (:message result) (str (:error result)))}]
     :isError true}))

(defn handle-read-file [params] (result->mcp (fc/read-file params)))
(defn handle-file-write [params] (result->mcp (fc/write-file params)))
(defn handle-glob-files [params] (result->mcp (fc/glob-files params)))
(defn handle-grep [params] (result->mcp (fc/grep-files params)))

;; =============================================================================
;; File Tool Definitions
;; =============================================================================

(defn read-file-tool-def []
  {:name        "read_file"
   :description "Read the contents of a file.

Parameters:
- path: Absolute path to the file
- offset: Line number to start from (optional, default: 0)
- limit: Max lines to read (optional, default: 2000)"
   :inputSchema {:type       "object"
                 :properties {:path   {:type "string"
                                       :description "Absolute path to the file"}
                              :offset {:type "integer"
                                       :description "Line to start from (default: 0)"}
                              :limit  {:type "integer"
                                       :description "Max lines to read (default: 2000)"}}
                 :required   ["path"]}})

(defn file-write-tool-def []
  {:name        "file_write"
   :description "Write content to a file. Creates parent directories if needed."
   :inputSchema {:type       "object"
                 :properties {:file_path {:type "string"
                                          :description "Absolute path to write"}
                              :content   {:type "string"
                                          :description "Content to write"}}
                 :required   ["file_path" "content"]}})

(defn glob-files-tool-def []
  {:name        "glob_files"
   :description "Find files matching a glob pattern.

Examples:
- glob_files(pattern: \"**/*.clj\")
- glob_files(pattern: \"src/**/*.cljs\", path: \"/project\")"
   :inputSchema {:type       "object"
                 :properties {:pattern {:type "string"
                                        :description "Glob pattern (e.g. **/*.clj)"}
                              :path    {:type "string"
                                        :description "Root directory (default: cwd)"}}
                 :required   ["pattern"]}})

(defn grep-tool-def []
  {:name        "grep"
   :description "Search for patterns in files using ripgrep.

Examples:
- grep(pattern: \"defn.*foo\")
- grep(pattern: \"TODO\", path: \"src/\", include: \"*.clj\")"
   :inputSchema {:type       "object"
                 :properties {:pattern     {:type "string"
                                            :description "Regex pattern to search"}
                              :path        {:type "string"
                                            :description "Directory to search (default: cwd)"}
                              :include     {:type "string"
                                            :description "File pattern to include (e.g. *.clj)"}
                              :max_results {:type "integer"
                                            :description "Max results (default: 100)"}}
                 :required   ["pattern"]}})

(defn file-tool-defs
  "All file tool definitions with handlers attached."
  []
  [(assoc (read-file-tool-def) :handler handle-read-file)
   (assoc (file-write-tool-def) :handler handle-file-write)
   (assoc (glob-files-tool-def) :handler handle-glob-files)
   (assoc (grep-tool-def) :handler handle-grep)])

;; Rename for clarity: clojure supertool def
(def clojure-tool-def tool-def)

(defn invalidate-cache! [] nil)
