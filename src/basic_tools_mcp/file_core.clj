(ns basic-tools-mcp.file-core
  "File I/O implementations for basic-tools-mcp.

   Returns Result ADT values: (ok text) or (err :io/... {:message ...}).
   JVM-compatible: uses slurp/spit, babashka.fs, clojure.java.shell."
  (:require [babashka.fs :as fs]
            [basic-tools-mcp.structural :as structural]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [hive-dsl.result :as r]))

(defn- format-numbered-lines
  "Format lines with line numbers, applying offset and limit."
  [text offset limit]
  (let [lines    (str/split-lines text)
        selected (->> lines (drop offset) (take limit))
        numbered (map-indexed
                  (fn [i line]
                    (format "%6d\u2192%s" (+ offset i 1) line))
                  selected)]
    (str/join "\n" numbered)))

(defn read-file
  "Read a file with optional offset and limit. Returns numbered lines."
  [{:keys [path offset limit]}]
  (let [offset (or offset 0)
        limit  (or limit 2000)]
    (if (fs/exists? path)
      (r/try-effect* :io/read-failure
                     (format-numbered-lines (slurp path) offset limit))
      (r/err :io/not-found {:message (str "File not found: " path)
                            :path path}))))

(defn write-file
  "Write content to a file. Creates parent directories if needed."
  [{:keys [file_path content]}]
  (r/try-effect* :io/write-failure
                 (let [parent (fs/parent file_path)]
                   (when (and parent (not (fs/exists? parent)))
                     (fs/create-dirs parent))
                   (spit file_path content)
                   (str "File written: " file_path))))

(defn glob-files
  "Find files matching a glob pattern."
  [{:keys [pattern path]}]
  (let [root (or path (System/getProperty "user.dir"))]
    (r/try-effect* :io/read-failure
                   (let [matches (->> (fs/glob root pattern)
                                      (map str)
                                      sort
                                      (take 1000))]
                     (if (seq matches)
                       (str/join "\n" matches)
                       "No matches found")))))

;; =============================================================================
;; Structural Editing
;; =============================================================================

(defn wrap-form
  "Structural wrap: locate form at line, wrap with template, write back.
   IO sandwich: read (IO) -> wrap-in-source (pure) -> write (IO)."
  [{:keys [file_path line template]}]
  (if (fs/exists? file_path)
    (r/let-ok [source     (r/try-effect* :io/read-failure (slurp file_path))
               new-source (structural/wrap-in-source source line template)]
              (r/try-effect* :io/write-failure
                             (spit file_path new-source)
                             (str "Form at line " line " wrapped successfully")))
    (r/err :io/not-found {:message (str "File not found: " file_path)})))

(defn validated-write-file
  "Write with pre-write delimiter validation for Clojure files.
   Anti-corruption layer: rejects unbalanced Clojure at the boundary."
  [{:keys [file_path content] :as params}]
  (if (and (structural/clojure-source-file? file_path)
           (not (structural/balanced? content)))
    (r/err :io/unbalanced-delimiters
           {:message "Content has unbalanced delimiters, write rejected"
            :file_path file_path})
    (write-file params)))

;; =============================================================================
;; Search
;; =============================================================================

(defn grep-files
  "Search for pattern using ripgrep."
  [{:keys [pattern path include max_results]}]
  (let [root  (or path ".")
        limit (or max_results 100)
        args  (cond-> ["rg" "--line-number" "--no-heading"]
                include (into ["--glob" include])
                :always (into [pattern root]))]
    (r/try-effect* :io/read-failure
                   (let [result (apply shell/sh args)
                         lines  (when (seq (:out result))
                                  (->> (str/split-lines (:out result))
                                       (take limit)))]
                     (if (seq lines)
                       (str/join "\n" lines)
                       "No matches found")))))
