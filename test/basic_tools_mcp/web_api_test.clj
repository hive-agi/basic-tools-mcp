(ns basic-tools-mcp.web-api-test
  (:require [clojure.test :refer [deftest is]]
            [basic-tools-mcp.web.api :as api]
            [basic-tools-mcp.web.collect :as collect]
            [basic-tools-mcp.web.search :as search]))

(deftest stale-default-backends-reseat-without-overwriting-live-overrides
  (let [old-fetcher (api/default-fetcher)
        old-searcher (api/default-searcher)
        old-emitter (api/default-emitter)
        stale (Object.)]
    (try
      (api/set-default-fetcher! stale)
      (api/set-default-searcher! stale)
      (api/set-default-emitter! stale)
      (is (satisfies? collect/IWebFetcher (api/default-fetcher)))
      (is (satisfies? search/IWebSearcher (api/default-searcher)))
      (is (satisfies? search/IWebSearchEmitter (api/default-emitter)))
      (let [f (api/default-fetcher) s (api/default-searcher) e (api/default-emitter)]
        (is (identical? f (api/default-fetcher)))
        (is (identical? s (api/default-searcher)))
        (is (identical? e (api/default-emitter))))
      (finally
        (api/set-default-fetcher! old-fetcher)
        (api/set-default-searcher! old-searcher)
        (api/set-default-emitter! old-emitter)))))
