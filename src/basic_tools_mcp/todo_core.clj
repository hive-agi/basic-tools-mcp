(ns basic-tools-mcp.todo-core
  "Session-scoped todo list — agent's working scratchpad.

   Distinct from kanban (project-level, persistent).

   ISP: ITodoStore protocol carves the operations agents actually need
   (read, replace-all, clear). Concrete backends:
     - InMemoryTodoStore : default, atom-backed, ephemeral
     - <future>          : KanbanTodoStore (lives in hive-agent), wraps
                           hive-mcp kanban for persistent agent journals
     - <future>          : DatalevinTodoStore for crash-resilient todos

   DIP: tools.clj depends on the protocol via convenience API
   `(default-store)` — never on a concrete backend.

   Replace-the-list semantics: each `write-todos!` call replaces the entire
   list for the given agent-id. When all items are :completed the list is
   cleared automatically (matches claude-code-haha TodoWriteTool behavior).

   TodoItem shape:
     {:content    \"Implement parser\"     ; non-blank
      :status     :pending|:in_progress|:completed
      :activeForm \"Implementing parser\"} ; non-blank, present-continuous"
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]))

;; =============================================================================
;; Validation (pure, shared across backends)
;; =============================================================================

(def ^:private valid-statuses
  #{:pending :in_progress :completed})

(defn- coerce-status
  [v]
  (cond
    (keyword? v) v
    (string?  v) (keyword (str/replace v #"-" "_"))
    :else        nil))

(defn- validate-item
  [{:keys [content status activeForm] :as item}]
  (cond
    (or (not (string? content)) (str/blank? content))
    (r/err :todo/invalid-item
           {:message "Each todo item must have a non-blank :content"
            :item item})

    (or (not (string? activeForm)) (str/blank? activeForm))
    (r/err :todo/invalid-item
           {:message "Each todo item must have a non-blank :activeForm"
            :item item})

    (not (contains? valid-statuses (coerce-status status)))
    (r/err :todo/invalid-status
           {:message (str "Invalid status: " (pr-str status)
                          " — must be one of " valid-statuses)
            :item item})

    :else
    (r/ok {:content    content
           :status     (coerce-status status)
           :activeForm activeForm})))

(defn- validate-list
  [items]
  (if-not (sequential? items)
    (r/err :todo/invalid-list
           {:message "todos must be a sequential collection of items"})
    (loop [acc [] [item & more] items]
      (if (nil? item)
        (r/ok acc)
        (let [v (validate-item item)]
          (if (r/ok? v)
            (recur (conj acc (:ok v)) more)
            v))))))

(defn- all-completed?
  [items]
  (and (seq items)
       (every? #(= :completed (:status %)) items)))

;; =============================================================================
;; ITodoStore Protocol  (ISP — minimal surface agents need)
;; =============================================================================

(defprotocol ITodoStore
  "Session-scoped todo storage abstraction.

   All operations return hive-dsl Result for railway composition.
   Implementations decide persistence semantics (ephemeral vs durable)."
  (-store-id [this]
    "Keyword identifying the backend (:in-memory, :kanban, ...).")
  (-read-todos [this agent-id]
    "Return Result<vec<TodoItem>>; empty vector when no list.")
  (-write-todos! [this agent-id validated-items]
    "Replace list. Receives already-validated items.
     Returns Result<{:old [..] :new [..] :stored [..] :all-completed? bool}>.")
  (-clear-todos! [this agent-id]
    "Drop list. Returns Result<{:cleared [..]}>.")
  (-list-agents [this]
    "Diagnostic. Returns Result<seq<agent-id>>."))

;; =============================================================================
;; InMemoryTodoStore — default backend (atom-based)
;; =============================================================================

(defrecord InMemoryTodoStore [state]
  ITodoStore
  (-store-id [_] :in-memory)

  (-read-todos [_ agent-id]
    (r/ok (get @state agent-id [])))

  (-write-todos! [_ agent-id validated]
    (let [old           (get @state agent-id [])
          ac?           (all-completed? validated)
          stored        (if ac? [] validated)]
      (swap! state assoc agent-id stored)
      (r/ok {:old            old
             :new            validated
             :stored         stored
             :all-completed? ac?})))

  (-clear-todos! [_ agent-id]
    (let [old (get @state agent-id [])]
      (swap! state dissoc agent-id)
      (r/ok {:cleared old})))

  (-list-agents [_]
    (r/ok (sort (keys @state)))))

(defn make-in-memory-store
  "Construct a fresh InMemoryTodoStore. For DI / test isolation."
  []
  (->InMemoryTodoStore (atom {})))

;; =============================================================================
;; Default Store (DIP convenience layer)
;; =============================================================================

(defonce ^:private default-store-ref
  (atom (make-in-memory-store)))

(defn default-store
  "Return the process-wide default ITodoStore.
   Hive-agent / hive-mcp can swap via `set-default-store!`."
  []
  @default-store-ref)

(defn set-default-store!
  "Install a different ITodoStore implementation as the process default.
   Returns the new store."
  [^basic_tools_mcp.todo_core.ITodoStore store]
  (reset! default-store-ref store)
  store)

;; =============================================================================
;; Public API — operates on default store unless given an explicit one
;; =============================================================================

(defn get-todos
  "Read current todo list for `agent-id`.
   Returns the list directly (empty vector when none) — convenience that
   unwraps the Result for callers that don't need error detail."
  ([agent-id] (get-todos (default-store) agent-id))
  ([store agent-id]
   (let [r (-read-todos store agent-id)]
     (if (r/ok? r) (:ok r) []))))

(defn write-todos!
  "Validate + replace todo list for `agent-id`.
   Returns Result<{:old :new :stored :all-completed?}>."
  ([agent-id items] (write-todos! (default-store) agent-id items))
  ([store agent-id items]
   (r/let-ok [validated (validate-list items)]
     (-write-todos! store agent-id validated))))

(defn clear-todos!
  ([agent-id] (clear-todos! (default-store) agent-id))
  ([store agent-id]
   (-clear-todos! store agent-id)))

(defn list-agents
  ([] (list-agents (default-store)))
  ([store]
   (let [r (-list-agents store)]
     (if (r/ok? r) (:ok r) []))))

(defn reset-all!
  "Test/REPL only — replace default store with a fresh in-memory instance."
  []
  (set-default-store! (make-in-memory-store)))
