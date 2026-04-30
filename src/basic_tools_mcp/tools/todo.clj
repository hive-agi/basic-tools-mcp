(ns basic-tools-mcp.tools.todo
  (:require [basic-tools-mcp.todo-core :as todo]
            [hive-dsl.result :as r]))

(declare handle-todo-write todo-write-tool-def)

(defn handle-todo-write
  "Replace the agent's session todo list. Caller passes :_caller_agent_id;
   falls back to :default when absent."
  [{:keys [todos _caller_agent_id]}]
  (let [agent-id (or _caller_agent_id :default)
        result   (todo/write-todos! agent-id (or todos []))]
    (if (r/ok? result)
      {:content [{:type "text"
                  :text (str "Todos updated for agent " agent-id ". "
                             (count (:new (:ok result))) " items, "
                             (count (filter #(= :completed (:status %))
                                            (:new (:ok result))))
                             " completed."
                             (when (:all-completed? (:ok result))
                               " All complete — list cleared."))}]}
      {:content [{:type "text" :text (or (:message result) (pr-str result))}]
       :isError true})))

(defn todo-write-tool-def []
  {:name        "todo_write"
   :description "Replace the agent's session todo list. Working scratchpad,
distinct from the project-level kanban tool.

Replace-the-list semantics: each call replaces the entire list. When every
item is :completed the list is cleared automatically.

Use this proactively for non-trivial multi-step tasks (3+ steps). Mark items
:in_progress BEFORE starting work, :completed when actually finished. Don't
batch completions — update as you go.

Each item:
- content    Imperative form (e.g. \"Fix auth bug\")
- status     pending | in_progress | completed
- activeForm Present-continuous form for spinner (e.g. \"Fixing auth bug\")"
   :inputSchema {:type       "object"
                 :properties {:todos {:type        "array"
                                      :description "The full updated todo list"
                                      :items       {:type       "object"
                                                    :properties {:content    {:type "string"}
                                                                 :status     {:type "string"
                                                                              :enum ["pending" "in_progress" "completed"]}
                                                                 :activeForm {:type "string"}}
                                                    :required   ["content" "status" "activeForm"]}}}
                 :required   ["todos"]}})
