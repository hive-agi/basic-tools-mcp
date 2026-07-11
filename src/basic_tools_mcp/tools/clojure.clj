(ns basic-tools-mcp.tools.clojure
  (:require [basic-tools-mcp.log :as log]
            [hive-dsl.result :as r]
            [basic-tools-mcp.clojure.service :as service]))

(declare resolve-source command-handlers handle-clojure tool-def clojure-tool-def invalidate-cache!)

(defn resolve-source
  "Backward-compatible facade over the Clojure application service."
  [params]
  (service/resolve-source params))

(defn handle-clojure
  "MCP adapter over the Result-returning Clojure application service."
  [{:keys [command] :as params}]
  (let [result (try
                 (service/execute params)
                 (catch Exception e
                   (log/error "clojure command failed:" command (ex-message e))
                   (r/err :command/exception {:command command
                                              :message (ex-message e)})))]
    (if (r/ok? result)
      {:content [{:type "text" :text (pr-str (:ok result))}]}
      {:content [{:type "text" :text (pr-str result)}]
       :isError true})))

(defn tool-def
  "MCP tool definition for the clojure tool."
  []
  {:name        "clojure"
   :description "Clojure dev tools: check, repair, format, eval, discover, and structural wrap"
   :inputSchema {:type       "object"
                 :properties {:command   {:type "string"
                                          :enum (sort service/available-commands)}
                              :code      {:type "string"
                                          :description "Clojure code string"}
                              :file_path {:type "string"
                                          :description "Path to Clojure file"}
                              :port      {:type "number"
                                          :description "nREPL port (for eval)"}
                              :host      {:type "string"
                                          :description "nREPL host (default: localhost)"}
                              :timeout   {:type "number"
                                          :description "Timeout in ms (default: 120000)"}
                              :line      {:type "integer"
                                          :description "1-based form line (for wrap)"}
                              :template  {:type "string"
                                          :description "Wrap template with %s placeholder"}}
                 :required   ["command"]}})

(def clojure-tool-def tool-def)

(defn invalidate-cache! [] nil)
