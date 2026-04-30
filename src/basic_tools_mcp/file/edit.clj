(ns basic-tools-mcp.file.edit
  (:require [basic-tools-mcp.edit-core :as edit-core]
            [basic-tools-mcp.structural :as structural]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-system.fs.core :as hfs]
            [hive-weave.safe :as weave]
            [basic-tools-mcp.file.path :as fp]))

(declare edit-file edit-read-timeout-ms)

(defn edit-file
  "Surgical text edit. IO sandwich: existence check (DIP via hive-system) →
   bounded slurp (hive-weave) → pure apply-edit (edit-core) → spit.

   Params:
     :file_path     absolute path
     :old_string    exact substring to replace
     :new_string    replacement (\"\" deletes)
     :replace_all   when true, replace every occurrence; default false
     :_caller_cwd   resolves :file_path if relative

   Returns Result. For Clojure files, validates balanced delimiters before
   writing — corrupted edits are rejected at the boundary."
  [{:keys [file_path old_string new_string replace_all _caller_cwd]}]
  (let [path (fp/resolve-path file_path _caller_cwd)]
    (cond
      (str/blank? path)
      (r/err :input/missing {:message "file_path is required"})

      (nil? old_string)
      (r/err :input/missing {:message "old_string is required"})

      (nil? new_string)
      (r/err :input/missing {:message "new_string is required"})

      :else
      (r/let-ok [exists? (hfs/exists? path)]
        (if-not exists?
          (r/err :io/not-found {:message (str "File not found: " path) :path path})
          (r/let-ok [content (weave/safe-future-call
                              {:timeout-ms edit-read-timeout-ms :name "edit-file/slurp"}
                              #(slurp path))
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
              (r/try-effect* :io/write-failure
                             (spit path updated)
                             (str "Edited " path)))))))))

(def ^:private edit-read-timeout-ms
  "Hard cap on file read for the Edit IO sandwich. Prevents hangs on
   pathological inputs (mounted-but-stuck FS, NFS staleness, huge files)."
  10000)
