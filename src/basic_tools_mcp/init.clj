(ns basic-tools-mcp.init
  "IAddon implementation for basic-tools-mcp — Clojure development tools.

   Contributes the 'clojure' MCP tool (delimiter repair, nREPL eval, formatting).
   Powered by clojure-mcp-light upstream. Overloadable by hive-knowledge.

   Usage:
     (init-as-addon!)    ;; Via addon system
     (register-tools!)   ;; Legacy fallback"
  (:require [basic-tools-mcp.tools :as tools]
            [basic-tools-mcp.log :as log]))

;; =============================================================================
;; Resolution Helpers
;; =============================================================================

(defn- try-resolve [sym]
  (try (requiring-resolve sym) (catch Exception _ nil)))

;; =============================================================================
;; IAddon Implementation
;; =============================================================================

(defonce ^:private addon-instance (atom nil))

(defn- make-addon []
  (when (try-resolve 'hive-mcp.addons.protocol/IAddon)
    (let [state (atom {:initialized? false})]
      (reify
        hive-mcp.addons.protocol/IAddon

        (addon-id [_] "basic-tools.mcp")

        (addon-type [_] :native)

        (capabilities [_] #{:tools})

        (initialize! [_ _config]
          (if (:initialized? @state)
            {:success? true :already-initialized? true}
            (do
              (reset! state {:initialized? true})
              (log/info "basic-tools-mcp addon initialized")
              {:success? true :errors [] :metadata {:tools 5}})))

        (shutdown! [_]
          (when (:initialized? @state)
            (tools/invalidate-cache!)
            (reset! state {:initialized? false}))
          nil)

        (tools [_]
          (into [(assoc (tools/tool-def) :handler tools/handle-clojure)]
                (tools/file-tool-defs)))

        (schema-extensions [_] {})

        (health [_]
          (if (:initialized? @state)
            {:status :ok :details {}}
            {:status :down :details {:reason "not initialized"}}))))))

;; =============================================================================
;; Nil-Railway Pipeline
;; =============================================================================

(defonce ^:private dep-registry
  (atom {:register! 'hive-mcp.addons.core/register-addon!
         :init!     'hive-mcp.addons.core/init-addon!
         :addon-id  'hive-mcp.addons.protocol/addon-id}))

(defn- resolve-deps [registry]
  (reduce-kv
   (fn [ctx k sym]
     (if-let [resolved (try-resolve sym)]
       (assoc ctx k resolved)
       (do (log/debug "Dep resolution failed:" k "->" sym)
           (reduced nil))))
   {} registry))

(defn- step-resolve-deps [ctx]
  (when-let [deps (resolve-deps @dep-registry)]
    (merge ctx deps)))

(defn- step-register [{:keys [addon register!] :as ctx}]
  (let [result (register! addon)]
    (when (:success? result)
      (assoc ctx :reg-result result))))

(defn- step-init [{:keys [addon addon-id init!] :as ctx}]
  (let [result (init! (addon-id addon))]
    (when (:success? result)
      (assoc ctx :init-result result))))

(defn- step-store-instance [{:keys [addon] :as ctx}]
  (reset! addon-instance addon)
  ctx)

(defn- run-addon-pipeline! [initial-ctx]
  (some-> initial-ctx
          step-resolve-deps
          step-register
          step-init
          step-store-instance))

;; =============================================================================
;; Public API
;; =============================================================================

(defn register-tools! []
  (into [(tools/tool-def)] (map #(dissoc % :handler) (tools/file-tool-defs))))

(defn init-as-addon! []
  (if-let [result (some-> (make-addon)
                          (as-> addon (run-addon-pipeline! {:addon addon})))]
    (do
      (log/info "basic-tools-mcp registered as IAddon")
      {:registered ["clojure" "read_file" "file_write" "glob_files" "grep"] :total 5})
    (do
      (log/debug "IAddon unavailable, falling back to legacy init")
      {:registered (mapv :name (register-tools!)) :total (count (register-tools!))})))

(defn get-addon-instance [] @addon-instance)
