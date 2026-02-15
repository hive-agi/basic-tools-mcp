(ns basic-tools-mcp.server
  "Standalone babashka MCP server for basic Clojure tools.

   Uses modex-bb framework for stdio JSON-RPC.
   Start: bb --config bb.edn run server"
  (:require [modex-bb.mcp.server :as mcp-server]
            [modex-bb.mcp.tools :refer [tools]]
            [basic-tools-mcp.core :as core]
            [basic-tools-mcp.file-core :as fc]
            [basic-tools-mcp.log :as log]))

;; =============================================================================
;; Tool Definitions (modex-bb DSL)
;; =============================================================================

(def clojure-tools
  (tools
   (check "Check Clojure code for delimiter errors (mismatched parens/brackets/braces)"
          [{:keys [code file_path]
            :type {:code :string :file_path :string}
            :doc  {:code "Clojure code string to check"
                   :file_path "Path to Clojure file to check"}
            :or   {code nil file_path nil}}]
          (let [text (cond
                       code code
                       file_path (slurp file_path)
                       :else (throw (ex-info "Provide 'code' or 'file_path'" {})))]
            [{:has-error (core/delimiter-error? text)
              :source (if code "inline" file_path)}]))

   (repair "Repair delimiter errors in Clojure code"
           [{:keys [code file_path]
             :type {:code :string :file_path :string}
             :doc  {:code "Clojure code string to repair"
                    :file_path "Path to Clojure file to repair (in-place)"}
             :or   {code nil file_path nil}}]
           (let [text (cond
                        code code
                        file_path (slurp file_path)
                        :else (throw (ex-info "Provide 'code' or 'file_path'" {})))
                 had-error (core/actual-delimiter-error? text)
                 result (core/repair-delimiters text)]
             (if (:success result)
               (do (when (and file_path (:text result))
                     (spit file_path (:text result)))
                   [{:success true :text (:text result)
                     :had-error had-error
                     :source (if code "inline" file_path)}])
               [{:success false
                 :error (or (:error result) "Repair failed")
                 :had-error had-error}])))

   (format_code "Format Clojure code with cljfmt"
                [{:keys [code file_path]
                  :type {:code :string :file_path :string}
                  :doc  {:code "Clojure code string to format"
                         :file_path "Path to Clojure file to format (in-place)"}
                  :or   {code nil file_path nil}}]
                (let [text (cond
                             code code
                             file_path (slurp file_path)
                             :else (throw (ex-info "Provide 'code' or 'file_path'" {})))
                      formatted (core/format-code text)]
                  (when file_path (spit file_path formatted))
                  [{:formatted formatted
                    :changed (not= text formatted)
                    :source (if code "inline" file_path)}]))

   (eval_code "Evaluate Clojure code via nREPL"
              [{:keys [code port host timeout]
                :type {:code :string :port :number :host :string :timeout :number}
                :doc  {:code "Clojure expression to evaluate"
                       :port "nREPL port number"
                       :host "nREPL host (default: localhost)"
                       :timeout "Timeout in ms (default: 120000)"}
                :or   {host "localhost" timeout 120000}}]
              [{:output (core/eval-code {:code code :port port
                                         :host host :timeout timeout})
                :host host :port port}])

   (discover "Discover nREPL servers running on this machine"
             [{:keys []
               :type {}
               :doc  {}}]
             (let [ports (core/discover-ports)]
               [{:ports ports :count (count ports)}]))))

(def file-tools
  (tools
   (read_file "Read the contents of a file.

Parameters:
- path: Absolute path to the file
- offset: Line number to start from (optional, default: 0)
- limit: Max lines to read (optional, default: 2000)"
              [{:keys [path offset limit]
                :type {:path :string :offset :integer :limit :integer}
                :doc  {:path "Absolute path to the file"
                       :offset "Line to start from (default: 0)"
                       :limit "Max lines to read (default: 2000)"}}]
              (let [result (fc/read-file {:path path :offset offset :limit limit})]
                [(:ok result)]))

   (file_write "Write content to a file. Creates parent directories if needed."
               [{:keys [file_path content]
                 :type {:file_path :string :content :string}
                 :doc  {:file_path "Absolute path to write"
                        :content "Content to write"}}]
               (let [result (fc/write-file {:file_path file_path :content content})]
                 [(:ok result)]))

   (glob_files "Find files matching a glob pattern.

Examples:
- glob_files(pattern: \"**/*.clj\")
- glob_files(pattern: \"src/**/*.cljs\", path: \"/project\")"
               [{:keys [pattern path]
                 :type {:pattern :string :path :string}
                 :doc  {:pattern "Glob pattern (e.g. **/*.clj)"
                        :path "Root directory (default: cwd)"}}]
               (let [result (fc/glob-files {:pattern pattern :path path})]
                 [(:ok result)]))

   (grep "Search for patterns in files using ripgrep.

Examples:
- grep(pattern: \"defn.*foo\")
- grep(pattern: \"TODO\", path: \"src/\", include: \"*.clj\")"
         [{:keys [pattern path include max_results]
           :type {:pattern :string :path :string :include :string :max_results :integer}
           :doc  {:pattern "Regex pattern to search"
                  :path "Directory to search (default: cwd)"
                  :include "File pattern to include (e.g. *.clj)"
                  :max_results "Max results (default: 100)"}}]
         (let [result (fc/grep-files {:pattern pattern :path path
                                      :include include :max_results max_results})]
           [(:ok result)]))))

;; =============================================================================
;; Server
;; =============================================================================

(def mcp-server
  (mcp-server/->server
   {:name    "basic-tools-mcp"
    :version "0.2.0"
    :tools   (merge clojure-tools file-tools)}))

(defn -main [& _args]
  (log/info "Starting basic-tools-mcp server v0.2.0")
  (mcp-server/start-server! mcp-server)
  (Thread/sleep 500))
