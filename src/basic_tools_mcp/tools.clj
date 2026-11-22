(ns basic-tools-mcp.tools
  "MCP tool handlers for basic Clojure development tools.

   Exposes handle-clojure for hive-mcp IAddon integration
   and tool-def for MCP schema registration.

   Commands: check, repair, format, eval, discover, wrap
   File tools: read_file, file_write, glob_files, grep"
  (:require
            [basic-tools-mcp.tools.clojure]
            [basic-tools-mcp.tools.file]
            [basic-tools-mcp.tools.todo]
            [basic-tools-mcp.tools.web]))

(declare handle-web-fetch handle-web-search web-fetch-tool-def web-search-tool-def)

(declare handle-todo-write todo-write-tool-def)

(declare result->mcp handle-read-file handle-file-write handle-edit handle-glob-files handle-grep read-file-tool-def file-write-tool-def edit-tool-def glob-files-tool-def grep-tool-def)

(declare resolve-source command-handlers handle-clojure tool-def clojure-tool-def invalidate-cache!)

;; =============================================================================
;; Command Helpers — Anti-corruption layer (DRY source resolution)
;; =============================================================================

;; =============================================================================
;; Command Handlers
;; =============================================================================

;; =============================================================================
;; MCP Interface (IAddon integration)
;; =============================================================================

;; =============================================================================
;; File Tool Handlers (standalone tools, not supertool command dispatch)
;; =============================================================================

;; =============================================================================
;; File Tool Definitions
;; =============================================================================

(defn file-tool-defs
  "All file tool definitions with handlers attached."
  []
  [(assoc (read-file-tool-def) :handler handle-read-file)
   (assoc (file-write-tool-def) :handler handle-file-write)
   (assoc (edit-tool-def) :handler handle-edit)
   (assoc (glob-files-tool-def) :handler handle-glob-files)
   (assoc (grep-tool-def) :handler handle-grep)
   (assoc (todo-write-tool-def) :handler handle-todo-write)
   (assoc (web-fetch-tool-def) :handler handle-web-fetch)
   (assoc (web-search-tool-def) :handler handle-web-search)])

;; Re-exports. Each one DELEGATES through the owning var at call time rather
;; than `(def x owner/x)`, which would capture the fn OBJECT: a reload of the
;; owner (a fix applied in a live image) would then never reach callers that
;; enter through this facade. See epic 20260821211613-0c0e618f.

(defn handle-clojure [& args] (apply basic-tools-mcp.tools.clojure/handle-clojure args))

(defn tool-def [& args] (apply basic-tools-mcp.tools.clojure/tool-def args))

(defn clojure-tool-def [& args] (apply basic-tools-mcp.tools.clojure/clojure-tool-def args))

(defn invalidate-cache! [& args] (apply basic-tools-mcp.tools.clojure/invalidate-cache! args))

(defn handle-read-file [& args] (apply basic-tools-mcp.tools.file/handle-read-file args))

(defn handle-file-write [& args] (apply basic-tools-mcp.tools.file/handle-file-write args))

(defn handle-edit [& args] (apply basic-tools-mcp.tools.file/handle-edit args))

(defn handle-glob-files [& args] (apply basic-tools-mcp.tools.file/handle-glob-files args))

(defn handle-grep [& args] (apply basic-tools-mcp.tools.file/handle-grep args))

(defn read-file-tool-def [& args] (apply basic-tools-mcp.tools.file/read-file-tool-def args))

(defn file-write-tool-def [& args] (apply basic-tools-mcp.tools.file/file-write-tool-def args))

(defn edit-tool-def [& args] (apply basic-tools-mcp.tools.file/edit-tool-def args))

(defn glob-files-tool-def [& args] (apply basic-tools-mcp.tools.file/glob-files-tool-def args))

(defn grep-tool-def [& args] (apply basic-tools-mcp.tools.file/grep-tool-def args))

(defn handle-todo-write [& args] (apply basic-tools-mcp.tools.todo/handle-todo-write args))

(defn todo-write-tool-def [& args] (apply basic-tools-mcp.tools.todo/todo-write-tool-def args))

(defn handle-web-fetch [& args] (apply basic-tools-mcp.tools.web/handle-web-fetch args))

(defn handle-web-search [& args] (apply basic-tools-mcp.tools.web/handle-web-search args))

(defn web-fetch-tool-def [& args] (apply basic-tools-mcp.tools.web/web-fetch-tool-def args))

(defn web-search-tool-def [& args] (apply basic-tools-mcp.tools.web/web-search-tool-def args))
