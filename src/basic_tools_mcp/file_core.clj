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
            [basic-tools-mcp.file.runtime :as runtime]))

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

(defn write-file
  "Write content to a file. Creates parent directories if needed.

   Rejects leaked LLM tool-call markup before crossing the write boundary."
  ([params]
   (write-file (runtime/default-runtime) params))
  ([{:keys [text-files]} {:keys [file_path content]}]
   (let [tags (edit-core/find-all-tool-call-fragments content)]
     (if (seq tags)
       (edit-core/tool-call-fragment-error "content" tags)
       (r/let-ok [_ (ports/write-text! text-files file_path content
                                       {:create-parents? true})]
         (r/ok (str "File written: " file_path)))))))

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
   (if (and (structural/clojure-source-file? file_path)
            (not (structural/balanced? content)))
     (r/err :io/unbalanced-delimiters
            {:message "Content has unbalanced delimiters, write rejected"
             :file_path file_path})
     (write-file runtime params))))

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
