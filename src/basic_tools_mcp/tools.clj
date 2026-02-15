(ns basic-tools-mcp.tools
  "MCP tool handlers for basic Clojure development tools.

   Exposes handle-clojure for hive-mcp IAddon integration
   and tool-def for MCP schema registration.

   Commands: check, repair, format, eval, discover"
  (:require [basic-tools-mcp.core :as core]
            [basic-tools-mcp.log :as log]))

;; =============================================================================
;; Command Handlers
;; =============================================================================

(def ^:private command-handlers
  {"check"    (fn [{:keys [code file_path]}]
                (let [text (cond
                             code code
                             file_path (try (slurp file_path)
                                            (catch Exception e {:error (.getMessage e)}))
                             :else nil)]
                  (cond
                    (nil? text)   {:error "Provide 'code' (string) or 'file_path'"}
                    (map? text)   text
                    :else         {:has-error (core/delimiter-error? text)
                                   :source (if code "inline" file_path)})))

   "repair"   (fn [{:keys [code file_path]}]
                (let [text (cond
                             code code
                             file_path (try (slurp file_path)
                                            (catch Exception e {:error (.getMessage e)}))
                             :else nil)]
                  (cond
                    (nil? text) {:error "Provide 'code' (string) or 'file_path'"}
                    (map? text) text
                    :else
                    (let [had-error (core/actual-delimiter-error? text)
                          result (core/repair-delimiters text)]
                      (if (:success result)
                        (do (when (and file_path (:text result))
                              (spit file_path (:text result)))
                            {:success true
                             :text (:text result)
                             :had-error had-error
                             :source (if code "inline" file_path)})
                        {:success false
                         :error (or (:error result) "Repair failed")
                         :had-error had-error})))))

   "format"   (fn [{:keys [code file_path]}]
                (let [text (cond
                             code code
                             file_path (try (slurp file_path)
                                            (catch Exception e {:error (.getMessage e)}))
                             :else nil)]
                  (cond
                    (nil? text) {:error "Provide 'code' (string) or 'file_path'"}
                    (map? text) text
                    :else
                    (try
                      (let [formatted (core/format-code text)]
                        (when file_path (spit file_path formatted))
                        {:formatted formatted
                         :changed (not= text formatted)
                         :source (if code "inline" file_path)})
                      (catch Exception e
                        {:error (str "Format failed: " (.getMessage e))})))))

   "eval"     (fn [{:keys [code port host timeout]}]
                (if (and code port)
                  (try
                    {:output (core/eval-code {:code code
                                              :port port
                                              :host host
                                              :timeout timeout})
                     :host (or host "localhost")
                     :port port}
                    (catch Exception e
                      {:error (str "Eval failed: " (.getMessage e))}))
                  {:error "Requires 'code' and 'port'"}))

   "discover" (fn [_params]
                (try
                  (let [ports (core/discover-ports)]
                    {:ports ports :count (count ports)})
                  (catch Exception e
                    {:error (str "Discovery failed: " (.getMessage e))})))})

;; =============================================================================
;; MCP Interface (IAddon integration)
;; =============================================================================

(defn handle-clojure
  "MCP tool handler for clojure commands. Dispatches on :command key.
   Returns MCP-compatible response map with :content vector."
  [{:keys [command] :as params}]
  (if-let [handler (get command-handlers command)]
    (try
      (let [result (handler params)]
        (if (:error result)
          {:content [{:type "text" :text (pr-str result)}]
           :isError true}
          {:content [{:type "text" :text (pr-str result)}]}))
      (catch Exception e
        (log/error "clojure command failed:" command (ex-message e))
        {:content [{:type "text" :text (pr-str {:error   "Failed to handle command"
                                                :command command
                                                :details (ex-message e)})}]
         :isError true}))
    {:content [{:type "text" :text (pr-str {:error     "Unknown command"
                                            :command   command
                                            :available (sort (keys command-handlers))})}]
     :isError true}))

(defn tool-def
  "MCP tool definition for the clojure tool."
  []
  {:name        "clojure"
   :description "Clojure dev tools: check (delimiter errors), repair (fix delimiters), format (cljfmt), eval (nREPL), discover (nREPL ports)"
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
                                          :description "Timeout in ms (default: 120000)"}}
                 :required   ["command"]}})

(defn invalidate-cache! [] nil)
