(ns basic-tools-mcp.server.clojure
  "bb MCP tool definitions for the Clojure supertool surface."
  (:require [modex-bb.mcp.tools :refer [tools]]
            [basic-tools-mcp.clojure.service :as service]
            [hive-dsl.result :as r]))

(defn- result->bb
  [result]
  (if (r/ok? result)
    [(:ok result)]
    (throw (ex-info (or (:message result) "Clojure command failed") result))))

(def clojure-tools
  (tools
   (check "Check Clojure code for delimiter or reader errors"
          [{:keys [code file_path]
            :type {:code :string :file_path :string}
            :doc  {:code "Clojure code string to check"
                   :file_path "Path to Clojure file to check"}
            :or   {code nil file_path nil}}]
          (result->bb
           (service/execute {:command "check"
                             :code code
                             :file_path file_path})))

   (repair "Repair delimiter errors in Clojure code"
           [{:keys [code file_path]
             :type {:code :string :file_path :string}
             :doc  {:code "Clojure code string to repair"
                    :file_path "Path to Clojure file to repair (in-place)"}
             :or   {code nil file_path nil}}]
           (result->bb
            (service/execute {:command "repair"
                              :code code
                              :file_path file_path})))

   (format_code "Format Clojure code with cljfmt"
                [{:keys [code file_path]
                  :type {:code :string :file_path :string}
                  :doc  {:code "Clojure code string to format"
                         :file_path "Path to Clojure file to format (in-place)"}
                  :or   {code nil file_path nil}}]
                (result->bb
                 (service/execute {:command "format"
                                   :code code
                                   :file_path file_path})))

   (eval_code "Evaluate Clojure code via nREPL"
              [{:keys [code port host timeout]
                :type {:code :string :port :number :host :string :timeout :number}
                :doc  {:code "Clojure expression to evaluate"
                       :port "nREPL port number"
                       :host "nREPL host (default: localhost)"
                       :timeout "Timeout in ms (default: 120000)"}
                :or   {host "localhost" timeout 120000}}]
              (result->bb
               (service/execute {:command "eval"
                                 :code code
                                 :port port
                                 :host host
                                 :timeout timeout})))

   (discover "Discover nREPL servers running on this machine"
             [{:keys []
               :type {}
               :doc  {}}]
             (result->bb (service/execute {:command "discover"})))))