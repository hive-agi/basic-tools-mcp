(ns basic-tools-mcp.server.clojure
  "bb MCP tool definitions for the Clojure supertool surface."
  (:require [modex-bb.mcp.tools :refer [tools]]
            [basic-tools-mcp.core :as core]))

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
