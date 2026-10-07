(ns basic-tools-mcp.server
  "Standalone babashka MCP server for basic Clojure tools.

   Uses modex-bb framework for stdio JSON-RPC.
   Start: bb --config bb.edn run server

   Tool groups live in basic-tools-mcp.server.{clojure,file,todo,web};
   this ns composes them into the MCP server surface."
  (:require [modex-bb.mcp.server :as mcp-server]
            [basic-tools-mcp.log :as log]
            [basic-tools-mcp.release :as release]
            [basic-tools-mcp.server.clojure :as srv-clojure]
            [basic-tools-mcp.server.file    :as srv-file]
            [basic-tools-mcp.server.todo    :as srv-todo]
            [basic-tools-mcp.server.web     :as srv-web]
            [clojure.java.io :as io]))

(def mcp-server
  (mcp-server/->server
   {:name    "basic-tools-mcp"
    :version (release/version #(slurp (io/resource "basic-tools-mcp/VERSION")))
    :tools   (merge srv-clojure/clojure-tools
                    srv-file/file-tools
                    srv-todo/todo-tools
                    srv-web/web-tools)}))

(defn -main [& _args]
  (log/info "Starting basic-tools-mcp server v" (release/version #(slurp (io/resource "basic-tools-mcp/VERSION"))))
  (mcp-server/start-server! mcp-server)
  (Thread/sleep 500))
