(ns basic-tools-mcp.clojure.service
  "Application service for the Clojure bounded context.

   Both MCP delivery surfaces call this namespace. It owns command validation
   and orchestration, while file effects are delegated through injected ports
   and parsing/formatting remain in the pure core."
  (:require [basic-tools-mcp.core :as core]
            [basic-tools-mcp.file-core :as file-core]
            [basic-tools-mcp.file.ports :as file-ports]
            [basic-tools-mcp.file.runtime :as runtime]
            [clojure.string :as str]
            [hive-dsl.result :as r]))

(def available-commands
  #{"check" "repair" "format" "eval" "discover" "wrap"})

(defn resolve-source
  "Promote inline or file input into {:text :source}."
  ([params]
   (resolve-source (runtime/default-runtime) params))
  ([{:keys [text-files]} {:keys [code file_path]}]
   (cond
     (some? code)
     (r/ok {:text code :source "inline"})

     (some? file_path)
     (r/let-ok [text (file-ports/read-text text-files file_path {})]
       (r/ok {:text text :source file_path}))

     :else
     (r/err :input/missing
            {:message "Provide 'code' (string) or 'file_path'"}))))

(defn- persist-result
  [runtime source text result]
  (if (= source "inline")
    (r/ok result)
    (r/let-ok [_ (file-ports/write-text! (:text-files runtime)
                                         source text
                                         {:create-parents? false})]
      (r/ok result))))

(defn- check-command
  [runtime params]
  (r/let-ok [{:keys [text source]} (resolve-source runtime params)]
    (let [reader-err (core/reader-error? text)]
      (r/ok (cond-> {:has-error (boolean reader-err)
                     :source    source}
              reader-err (assoc :message (:message reader-err)))))))

(defn- repair-command
  [runtime params]
  (r/let-ok [{:keys [text source]} (resolve-source runtime params)]
    (let [had-error (core/actual-delimiter-error? text)
          result    (core/repair-delimiters text)]
      (if (:success result)
        (let [repaired (:text result)
              response {:success   true
                        :text      repaired
                        :had-error had-error
                        :source    source}]
          (if repaired
            (persist-result runtime source repaired response)
            (r/ok response)))
        (r/err :repair/failed
               {:message   (or (:error result) "Repair failed")
                :had-error had-error})))))

(defn- format-command
  [runtime params]
  (r/let-ok [{:keys [text source]} (resolve-source runtime params)
             formatted            (core/format-code text)]
    (persist-result runtime source formatted
                    {:formatted formatted
                     :changed   (not= text formatted)
                     :source    source})))

(defn- eval-command
  [{:keys [code port host timeout]}]
  (if (and code port)
    (r/let-ok [output (core/eval-code {:code code :port port
                                       :host host :timeout timeout})]
      (r/ok {:output output
             :host   (or host "localhost")
             :port   port}))
    (r/err :input/missing {:message "Requires 'code' and 'port'"})))

(defn- discover-command
  []
  (r/let-ok [ports (core/discover-ports)]
    (r/ok {:ports ports :count (count ports)})))

(defn- wrap-command
  [runtime {:keys [file_path line template]}]
  (cond
    (nil? file_path)
    (r/err :input/missing {:message "file_path is required"})

    (nil? line)
    (r/err :input/missing {:message "line is required"})

    (nil? template)
    (r/err :input/missing
           {:message "template is required (use %s as placeholder)"})

    (not (str/includes? template "%s"))
    (r/err :input/invalid {:message "template must contain %s placeholder"})

    :else
    (r/let-ok [message (file-core/wrap-form runtime
                                             {:file_path file_path
                                              :line      line
                                              :template  template})]
      (r/ok {:success true :message message}))))

(defn execute
  "Execute one Clojure command and return Result. Runtime arity enables DI."
  ([params]
   (execute (runtime/default-runtime) params))
  ([runtime {:keys [command] :as params}]
   (case command
     "check"    (check-command runtime params)
     "repair"   (repair-command runtime params)
     "format"   (format-command runtime params)
     "eval"     (eval-command params)
     "discover" (discover-command)
     "wrap"     (wrap-command runtime params)
     (r/err :command/unknown
            {:command   command
             :available (sort available-commands)}))))
