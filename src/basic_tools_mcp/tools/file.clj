(ns basic-tools-mcp.tools.file
  (:require [basic-tools-mcp.file-core :as fc]
            [hive-dsl.result :as r]))

(declare result->mcp handle-read-file handle-file-write handle-edit handle-glob-files handle-grep read-file-tool-def file-write-tool-def edit-tool-def glob-files-tool-def grep-tool-def)

(defn result->mcp
  "Convert Result ADT to MCP response format.
   (ok text) => {:content [{:type \"text\" :text text}]}
   (err cat) => {:content [{:type \"text\" :text msg}] :isError true}"
  [result]
  (if (r/ok? result)
    {:content [{:type "text" :text (:ok result)}]}
    {:content [{:type "text" :text (or (:message result) (str (:error result)))}]
     :isError true}))

(defn handle-read-file [params] (result->mcp (fc/read-file params)))

(defn handle-file-write [params] (result->mcp (fc/write-file params)))

(defn handle-edit [params] (result->mcp (fc/edit-file params)))

(defn handle-glob-files [params] (result->mcp (fc/glob-files params)))

(defn handle-grep [params] (result->mcp (fc/grep-files params)))

(defn read-file-tool-def []
  {:name        "read_file"
   :description "Read the contents of a file.

Parameters:
- path: Absolute path to the file
- offset: Line number to start from (optional, default: 0)
- limit: Max lines to read (optional, default: 2000)"
   :inputSchema {:type       "object"
                 :properties {:path   {:type "string"
                                       :description "Absolute path to the file"}
                              :offset {:type "integer"
                                       :description "Line to start from (default: 0)"}
                              :limit  {:type "integer"
                                       :description "Max lines to read (default: 2000)"}}
                 :required   ["path"]}})

(defn file-write-tool-def []
  {:name        "file_write"
   :description "Write content to a file. Creates parent directories if needed."
   :inputSchema {:type       "object"
                 :properties {:file_path {:type "string"
                                          :description "Absolute path to write"}
                              :content   {:type "string"
                                          :description "Content to write"}}
                 :required   ["file_path" "content"]}})

(defn edit-tool-def []
  {:name        "edit"
   :description "Surgical text edit. Replaces an exact substring in a file.

The old_string must match EXACTLY (whitespace, indent, line endings). For
ambiguous matches (multiple occurrences) either expand context until unique
or pass replace_all=true. To delete content, use new_string=\"\".

For Clojure source files (.clj/.cljc/.cljs/.edn) the post-edit content is
validated for balanced delimiters; unbalanced edits are rejected.

For form-level Clojure edits prefer the structural codebase-map write-form
tool — Edit is the line/text-level fallback."
   :inputSchema {:type       "object"
                 :properties {:file_path   {:type        "string"
                                            :description "Absolute path to the file"}
                              :old_string  {:type        "string"
                                            :description "Exact substring to replace"}
                              :new_string  {:type        "string"
                                            :description "Replacement text (empty deletes)"}
                              :replace_all {:type        "boolean"
                                            :description "Replace every occurrence (default false)"}}
                 :required   ["file_path" "old_string" "new_string"]}})

(defn glob-files-tool-def []
  {:name        "glob_files"
   :description "Find files matching a glob pattern.

Examples:
- glob_files(pattern: \"**/*.clj\")
- glob_files(pattern: \"src/**/*.cljs\", path: \"/project\")"
   :inputSchema {:type       "object"
                 :properties {:pattern {:type "string"
                                        :description "Glob pattern (e.g. **/*.clj)"}
                              :path    {:type "string"
                                        :description "Root directory (default: cwd)"}}
                 :required   ["pattern"]}})

(defn grep-tool-def []
  {:name        "grep"
   :description "Search for patterns in files using ripgrep.

Examples:
- grep(pattern: \"defn.*foo\")
- grep(pattern: \"TODO\", path: \"src/\", include: \"*.clj\")"
   :inputSchema {:type       "object"
                 :properties {:pattern     {:type "string"
                                            :description "Regex pattern to search"}
                              :path        {:type "string"
                                            :description "Directory to search (default: cwd)"}
                              :include     {:type "string"
                                            :description "File pattern to include (e.g. *.clj)"}
                              :max_results {:type "integer"
                                            :description "Max results (default: 100)"}}
                 :required   ["pattern"]}})
