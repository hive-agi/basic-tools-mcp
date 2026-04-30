(ns basic-tools-mcp.file-core
  "File I/O implementations for basic-tools-mcp.

   Returns Result ADT values: (ok text) or (err :io/... {:message ...}).
   JVM-compatible: uses slurp/spit, babashka.fs, clojure.java.shell.

   Path predicates route through hive-system.fs.core (DIP).
   Bounded execution uses hive-weave.safe."
  (:require [babashka.fs :as fs]
            [basic-tools-mcp.edit-core :as edit-core]
            [basic-tools-mcp.structural :as structural]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-system.fs.core :as hfs]
            [hive-weave.safe :as weave]
            [basic-tools-mcp.file.edit :as edit]
            [basic-tools-mcp.file.search :as search]
            [basic-tools-mcp.file.path :as fp]))

(declare edit-file glob-files grep-files)

(defn read-file
  "Read a file with optional offset and limit. Returns numbered lines."
  [{:keys [path offset limit _caller_cwd]}]
  (let [resolved (fp/resolve-path path _caller_cwd)
        offset   (or offset 0)
        limit    (or limit 2000)]
    (if (fs/exists? resolved)
      (r/try-effect* :io/read-failure
                     (fp/format-numbered-lines (slurp resolved) offset limit))
      (r/err :io/not-found {:message (str "File not found: " resolved)
                            :path resolved}))))

(defn write-file
  "Write content to a file. Creates parent directories if needed."
  [{:keys [file_path content]}]
  (r/try-effect* :io/write-failure
                 (let [parent (fs/parent file_path)]
                   (when (and parent (not (fs/exists? parent)))
                     (fs/create-dirs parent))
                   (spit file_path content)
                   (str "File written: " file_path))))

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

(def edit-file basic-tools-mcp.file.edit/edit-file)

(def glob-files basic-tools-mcp.file.search/glob-files)

(def grep-files basic-tools-mcp.file.search/grep-files)
