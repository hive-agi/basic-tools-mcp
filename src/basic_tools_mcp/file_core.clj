(ns basic-tools-mcp.file-core
  "File I/O implementations for basic-tools-mcp.

   Returns Result ADT values: (ok text) or (err :io/... {:message ...}).
   JVM-compatible: uses slurp/spit, babashka.fs, clojure.java.shell.

   Path predicates route through hive-system.fs.core (DIP).
   Bounded execution uses hive-weave.safe."
  (:require [basic-tools-mcp.edit-core :as edit-core]
            [basic-tools-mcp.structural :as structural]
            [hive-dsl.result :as r]
            [basic-tools-mcp.file.edit :as edit]
            [basic-tools-mcp.file.search :as search]
            [basic-tools-mcp.file.path :as fp]
            [hive-system.protocols :as system]
            [basic-tools-mcp.file.ports :as ports]
            [basic-tools-mcp.file.runtime :as runtime]
            [clojure.string :as str]))

(declare edit-file glob-files grep-files)

(defn read-file
  "Read a file with optional offset and limit. Returns numbered lines."
  ([params]
   (read-file (runtime/default-runtime) params))
  ([{:keys [path-query text-files]} {:keys [path offset limit _caller_cwd]}]
   (let [resolved (fp/resolve-path path _caller_cwd)
         offset   (or offset 0)
         limit    (or limit 2000)]
     (r/let-ok [exists? (system/path-exists? path-query resolved)]
       (if-not exists?
         (r/err :io/not-found {:message (str "File not found: " resolved)
                               :path resolved})
         (r/let-ok [text (ports/read-text text-files resolved {})]
           (r/ok (fp/format-numbered-lines text offset limit))))))))

(defn validate-write-content
  "Pure Promote step: reject missing content and accidental truncation.

   existing-text is nil for a missing target, otherwise the current text."
  [{:keys [content allow_empty] :as params} existing-text]
  (cond
    (nil? content)
    (let [text-like-keys (->> (keys params)
                              (filter #(and (keyword? %)
                                            (not= % :content)
                                            (some (fn [part]
                                                    (str/includes? (name %) part))
                                                  ["text" "body" "content" "string"])))
                              (sort-by name)
                              (mapv name))]
      (r/err :input/missing
             {:message (str "content is required"
                            (when (seq text-like-keys)
                              (str "; unknown text-like keys: "
                                   (str/join ", " text-like-keys))))
              :unknown-text-keys text-like-keys}))

    (not (string? content))
    (r/err :input/invalid {:message "content must be a string"})

    (and (empty? content) (seq existing-text) (not (true? allow_empty)))
    (r/err :input/empty-overwrite
           {:message "content is empty; pass allow_empty true to empty an existing non-empty file"})

    :else (r/ok content)))

(defn write-file
  "Write content to a file. Creates parent directories if needed.

   Validates params before any file effect; checks existing text before emptying.
   Rejects leaked LLM tool-call markup before crossing the write boundary."
  ([params]
   (write-file (runtime/default-runtime) params))
  ([{:keys [path-query text-files]} {:keys [file_path content] :as params}]
   (r/let-ok [_ (validate-write-content params nil)]
     (let [tags (edit-core/find-all-tool-call-fragments content)]
       (if (seq tags)
         (edit-core/tool-call-fragment-error "content" tags)
         (r/let-ok [existing (if (empty? content)
                                (r/let-ok [exists? (system/path-exists? path-query file_path)]
                                  (if exists?
                                    (ports/read-text text-files file_path {})
                                    (r/ok nil)))
                                (r/ok nil))
                    _ (validate-write-content params existing)
                    _ (ports/write-text! text-files file_path content
                                         {:create-parents? true})]
           (r/ok (str "File written: " file_path))))))))

;; =============================================================================
;; Structural Editing
;; =============================================================================

(defn wrap-form
  "Structural wrap: bounded read -> pure transform -> bounded write."
  ([params]
   (wrap-form (runtime/default-runtime) params))
  ([{:keys [path-query text-files]} {:keys [file_path line template]}]
   (r/let-ok [exists? (system/path-exists? path-query file_path)]
     (if-not exists?
       (r/err :io/not-found {:message (str "File not found: " file_path)})
       (r/let-ok [source     (ports/read-text text-files file_path {})
                  new-source (structural/wrap-in-source source line template)
                  _          (ports/write-text! text-files file_path new-source
                                                {:create-parents? false})]
         (r/ok (str "Form at line " line " wrapped successfully")))))))

(defn validated-write-file
  "Write with pre-write delimiter validation for Clojure files."
  ([params]
   (validated-write-file (runtime/default-runtime) params))
  ([runtime {:keys [file_path content] :as params}]
   (r/let-ok [_ (validate-write-content params nil)]
     (if (and (structural/clojure-source-file? file_path)
              (not (structural/balanced? content)))
       (r/err :io/unbalanced-delimiters
              {:message "Content has unbalanced delimiters, write rejected"
               :file_path file_path})
       (write-file runtime params)))))

;; =============================================================================
;; Search
;; =============================================================================

(defn edit-file
  ([params] (edit/edit-file params))
  ([runtime params] (edit/edit-file runtime params)))

(defn glob-files
  ([params] (search/glob-files params))
  ([runtime params] (search/glob-files runtime params)))

(defn grep-files
  ([params] (search/grep-files params))
  ([runtime params] (search/grep-files runtime params)))
