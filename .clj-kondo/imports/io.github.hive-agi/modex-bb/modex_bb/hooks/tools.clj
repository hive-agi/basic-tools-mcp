(ns modex-bb.hooks.tools
  "clj-kondo hooks for the modex-bb tool-definition DSL."
  (:require [clj-kondo.hooks-api :as api]))

(def ^:private metadata-keys #{:type :doc})

(defn- cleaned-map-node [node]
  (api/map-node
   (vec
    (mapcat identity
            (remove (fn [[key-node _]]
                      (contains? metadata-keys (api/sexpr key-node)))
                    (partition 2 (:children node)))))))

(defn- cleaned-args-node [args-node]
  (let [children (:children args-node)
        first-node (first children)]
    (if (= :map (:tag first-node))
      (api/vector-node
       (into [(cleaned-map-node first-node)] (rest children)))
      args-node)))

(defn- tool-fn-node [tool-def-node]
  (let [[_tool-name & tail] (:children tool-def-node)
        tail              (if (string? (api/sexpr (first tail)))
                            (rest tail)
                            tail)
        [args-node & body] tail]
    (api/list-node
     (list* (api/token-node 'fn)
            (cleaned-args-node args-node)
            body))))

(defn- defs-vector [tool-def-nodes]
  (api/vector-node (mapv tool-fn-node tool-def-nodes)))

(defn tool
  "Analyze one tool definition as an anonymous function."
  [{:keys [node]}]
  (let [[_ tool-def-node] (:children node)]
    {:node (tool-fn-node tool-def-node)}))

(defn tools
  "Analyze every tool handler while treating tool names and metadata as DSL data."
  [{:keys [node]}]
  {:node (defs-vector (rest (:children node)))})

(defn deftools
  "Register the generated var and analyze every tool handler."
  [{:keys [node]}]
  (let [[_ name-node & tool-defs] (:children node)]
    {:node (api/list-node
            [(api/token-node 'do)
             (api/list-node
              [(api/token-node 'def) name-node (api/token-node nil)])
             (defs-vector tool-defs)])}))

(defn handler
  "Analyze handler's metadata-bearing map argument as real destructuring."
  [{:keys [node]}]
  (let [[_ args-node & body] (:children node)]
    {:node (api/list-node
            (list* (api/token-node 'fn)
                   (cleaned-args-node args-node)
                   body))}))
