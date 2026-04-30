(ns basic-tools-mcp.web.api
  "CPPB Boundary — process-default backends + public convenience API.

   Holds the swappable singletons:
     default-fetcher  : IWebFetcher        (default JvmHttpFetcher)
     default-searcher : IWebSearcher       (default NullWebSearcher)
     default-emitter  : IWebSearchEmitter  (default NoopEmitter)

   Downstream wires real impls via set-default-*. Tools.clj depends only
   on this ns — never on a concrete record."
  (:require [basic-tools-mcp.web.collect :as collect]
            [basic-tools-mcp.web.search :as search]))

;; =============================================================================
;; Default Backends
;; =============================================================================

(defonce ^:private default-fetcher-ref  (atom (collect/make-jvm-fetcher)))
(defonce ^:private default-searcher-ref (atom (search/make-null-searcher)))
(defonce ^:private default-emitter-ref  (atom (search/make-noop-emitter)))

(defn default-fetcher  [] @default-fetcher-ref)
(defn default-searcher [] @default-searcher-ref)
(defn default-emitter  [] @default-emitter-ref)

(defn set-default-fetcher!  [f] (reset! default-fetcher-ref  f) f)
(defn set-default-searcher! [s] (reset! default-searcher-ref s) s)
(defn set-default-emitter!  [e] (reset! default-emitter-ref  e) e)

;; =============================================================================
;; Public API
;; =============================================================================

(defn fetch
  "Fetch a URL. Convenience over `(default-fetcher)`."
  ([url]              (fetch (default-fetcher) url {}))
  ([url opts]         (fetch (default-fetcher) url opts))
  ([fetcher url opts] (collect/-fetch fetcher url opts)))

(defn search
  "Run a web search. Streams hits through the configured emitter and
   returns the final Result for atomic callers.

   3-arity uses default searcher + default emitter.
   4-arity lets callers supply an ad-hoc emitter (e.g. REPL inspector)."
  ([query]                          (search (default-searcher) (default-emitter) query {}))
  ([query opts]                     (search (default-searcher) (default-emitter) query opts))
  ([searcher query opts]            (search searcher           (default-emitter) query opts))
  ([searcher emitter query opts]    (search/-search searcher emitter query opts)))
