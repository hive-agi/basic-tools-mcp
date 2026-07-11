(ns basic-tools-mcp.file.edit
  (:require [basic-tools-mcp.edit-core :as edit-core]
            [basic-tools-mcp.structural :as structural]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [basic-tools-mcp.file.path :as fp]
            [hive-system.protocols :as system]
            [basic-tools-mcp.file.ports :as ports]
            [basic-tools-mcp.file.runtime :as runtime]))

(declare edit-file edit-read-timeout-ms)

(defn edit-file
  "Surgical text edit through injected host ports.

   Existence is queried through hive-system IPathQuery. Reads and writes use
   the hive-weave-bounded text adapter. Pure matching and validation stay in
   edit-core and structural."
  ([params]
   (edit-file (runtime/default-runtime) params))
  ([{:keys [path-query text-files]}
    {:keys [file_path old_string new_string replace_all _caller_cwd]}]
   (let [path (fp/resolve-path file_path _caller_cwd)]
     (cond
       (str/blank? path)
       (r/err :input/missing {:message "file_path is required"})

       (nil? old_string)
       (r/err :input/missing {:message "old_string is required"})

       (nil? new_string)
       (r/err :input/missing {:message "new_string is required"})

       :else
       (r/let-ok [exists? (system/path-exists? path-query path)]
         (if-not exists?
           (r/err :io/not-found {:message (str "File not found: " path)
                                 :path path})
           (r/let-ok [content (ports/read-text text-files path
                                              {:timeout-ms edit-read-timeout-ms})
                      updated (edit-core/apply-edit
                               {:content      content
                                :old-string   old_string
                                :new-string   new_string
                                :replace-all? (boolean replace_all)})]
             (if (and (structural/clojure-source-file? path)
                      (not (structural/balanced? updated)))
               (r/err :io/unbalanced-delimiters
                      {:message "Edited content has unbalanced delimiters; edit rejected"
                       :file_path path})
               (r/let-ok [_ (ports/write-text! text-files path updated
                                               {:create-parents? false})]
                 (r/ok (str "Edited " path)))))))))))

(def ^:private edit-read-timeout-ms
  "Hard cap on file read for the Edit IO sandwich. Prevents hangs on
   pathological inputs (mounted-but-stuck FS, NFS staleness, huge files)."
  10000)
