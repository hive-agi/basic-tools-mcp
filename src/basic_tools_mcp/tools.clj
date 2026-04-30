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

;; Rename for clarity: clojure supertool def

(def handle-clojure basic-tools-mcp.tools.clojure/handle-clojure)

(def tool-def basic-tools-mcp.tools.clojure/tool-def)

(def clojure-tool-def basic-tools-mcp.tools.clojure/clojure-tool-def)

(def invalidate-cache! basic-tools-mcp.tools.clojure/invalidate-cache!)

(def handle-read-file basic-tools-mcp.tools.file/handle-read-file)

(def handle-file-write basic-tools-mcp.tools.file/handle-file-write)

(def handle-edit basic-tools-mcp.tools.file/handle-edit)

(def handle-glob-files basic-tools-mcp.tools.file/handle-glob-files)

(def handle-grep basic-tools-mcp.tools.file/handle-grep)

(def read-file-tool-def basic-tools-mcp.tools.file/read-file-tool-def)

(def file-write-tool-def basic-tools-mcp.tools.file/file-write-tool-def)

(def edit-tool-def basic-tools-mcp.tools.file/edit-tool-def)

(def glob-files-tool-def basic-tools-mcp.tools.file/glob-files-tool-def)

(def grep-tool-def basic-tools-mcp.tools.file/grep-tool-def)

(def handle-todo-write basic-tools-mcp.tools.todo/handle-todo-write)

(def todo-write-tool-def basic-tools-mcp.tools.todo/todo-write-tool-def)

(def handle-web-fetch basic-tools-mcp.tools.web/handle-web-fetch)

(def handle-web-search basic-tools-mcp.tools.web/handle-web-search)

(def web-fetch-tool-def basic-tools-mcp.tools.web/web-fetch-tool-def)

(def web-search-tool-def basic-tools-mcp.tools.web/web-search-tool-def)
