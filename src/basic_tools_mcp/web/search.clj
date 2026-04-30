(ns basic-tools-mcp.web.search
  "CPPB Pipeline — IWebSearcher protocol + IWebSearchEmitter event hook.

   Search results stream through `-emit-result!` per hit so downstream
   consumers (hive-events bus, websocket channel, terminal sink) receive
   results as they land — not just the final list.

   basic-tools-mcp stays event-bus-agnostic. Downstream wires a real
   emitter via web.api/set-default-emitter!.

   Default impls:
     NullWebSearcher : returns :web/no-searcher with config guidance
     NoopEmitter     : silent — caller still gets the final Result"
  (:require [hive-dsl.result :as r]))

;; =============================================================================
;; Protocols
;; =============================================================================

(defprotocol IWebSearcher
  "Query → search results. Each hit is also pushed through `emitter` for
   streaming consumers."
  (-searcher-id [this])
  (-search [this emitter query opts]
    "Opts: :max-results (10), :allowed-domains, :blocked-domains.
     Returns Result<{:query :results [{:title :url :snippet}] :provider}>."))

(defprotocol IWebSearchEmitter
  "Streaming sink for search hits. Side-effecting; return value unused."
  (-emitter-id [this])
  (-emit-result! [this hit]
    "Push a single search hit ({:title :url :snippet}) downstream.")
  (-emit-done! [this summary]
    "Signal end of stream — receives the final Result map."))

;; =============================================================================
;; NullWebSearcher — placeholder, always errs with config guidance
;; =============================================================================

(defrecord NullWebSearcher []
  IWebSearcher
  (-searcher-id [_] :null)
  (-search [_ emitter _query _opts]
    (let [err (r/err :web/no-searcher
                     {:message (str "No web search backend configured. "
                                    "Install a provider downstream and call "
                                    "basic-tools-mcp.web.api/set-default-searcher!. "
                                    "Built-in choices: Brave, Tavily, SearXNG, "
                                    "Anthropic beta web_search.")
                      :hint    "set-default-searcher!"})]
      (-emit-done! emitter err)
      err)))

(defn make-null-searcher [] (->NullWebSearcher))

;; =============================================================================
;; NoopEmitter — silent default
;; =============================================================================

(defrecord NoopEmitter []
  IWebSearchEmitter
  (-emitter-id [_]   :noop)
  (-emit-result! [_ _hit] nil)
  (-emit-done!   [_ _summary] nil))

(defn make-noop-emitter [] (->NoopEmitter))

;; =============================================================================
;; CallbackEmitter — fn-backed adapter for ad-hoc subscribers
;; =============================================================================
;; `on-result` and `on-done` are 1-arg callbacks. Useful for tests, REPL,
;; and downstream wiring before a full event bus is available.

(defrecord CallbackEmitter [on-result on-done]
  IWebSearchEmitter
  (-emitter-id [_]      :callback)
  (-emit-result! [_ hit]      (when on-result (on-result hit)))
  (-emit-done!   [_ summary]  (when on-done   (on-done   summary))))

(defn make-callback-emitter
  "Build a CallbackEmitter from optional :on-result and :on-done fns."
  [{:keys [on-result on-done]}]
  (->CallbackEmitter on-result on-done))
