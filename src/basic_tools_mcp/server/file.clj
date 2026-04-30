(ns basic-tools-mcp.server.file
  "bb MCP tool definitions for read/write/edit/glob/grep."
  (:require [modex-bb.mcp.tools :refer [tools]]
            [basic-tools-mcp.file-core :as fc]))

(def file-tools
  (tools
   (read_file "Read the contents of a file.

Parameters:
- path: Absolute path to the file
- offset: Line number to start from (optional, default: 0)
- limit: Max lines to read (optional, default: 2000)"
              [{:keys [path offset limit]
                :type {:path :string :offset :number :limit :number}
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

   (edit "Surgical text edit. Replaces an exact substring in a file.

old_string must match EXACTLY (whitespace, indent, line endings). For
ambiguous matches either expand context or pass replace_all=true. To
delete content, use new_string=\"\".

For Clojure source files (.clj/.cljc/.cljs/.edn) the post-edit content is
validated for balanced delimiters; unbalanced edits are rejected.

For form-level Clojure edits prefer codebase-map write-form."
         [{:keys [file_path old_string new_string replace_all]
           :type {:file_path :string :old_string :string :new_string :string :replace_all :string}
           :doc  {:file_path   "Absolute path to the file"
                  :old_string  "Exact substring to replace"
                  :new_string  "Replacement text (empty deletes)"
                  :replace_all "Replace every occurrence (default false)"}}]
         (let [result (fc/edit-file {:file_path file_path :old_string old_string
                                     :new_string new_string :replace_all replace_all})]
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
           :type {:pattern :string :path :string :include :string :max_results :number}
           :doc  {:pattern "Regex pattern to search"
                  :path "Directory to search (default: cwd)"
                  :include "File pattern to include (e.g. *.clj)"
                  :max_results "Max results (default: 100)"}}]
         (let [result (fc/grep-files {:pattern pattern :path path
                                      :include include :max_results max_results})]
           [(:ok result)]))))
