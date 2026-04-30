(ns basic-tools-mcp.server.todo
  "bb MCP tool definition for the session-scoped todo_write tool."
  (:require [modex-bb.mcp.tools :refer [tools]]
            [basic-tools-mcp.todo-core :as todo]))

(def todo-tools
  (tools
   (todo_write "Replace the agent's session todo list. Working scratchpad,
distinct from the project-level kanban tool.

Replace-the-list semantics: each call replaces the entire list. When every
item is :completed the list is cleared automatically.

Use proactively for non-trivial multi-step tasks (3+ steps). Mark items
in_progress BEFORE starting work, completed when actually finished."
         [{:keys [todos]
           :type {:todos :string}
           :doc  {:todos "Array of {content, status, activeForm} maps. status one of pending|in_progress|completed."}}]
         (let [result (todo/write-todos! :default (or todos []))]
           [(:ok result)]))))
