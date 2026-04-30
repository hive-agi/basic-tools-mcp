(ns basic-tools-mcp.server.web
  "bb MCP tool definitions for web_fetch and web_search."
  (:require [modex-bb.mcp.tools :refer [tools]]
            [basic-tools-mcp.web.api :as web]
            [hive-dsl.result :as r]))

(def web-tools
  (tools
   (web_fetch "Fetch content from a URL. Returns plain text by default
(HTML auto-stripped). Set as_text=false for raw bytes/JSON."
         [{:keys [url timeout_ms as_text]
           :type {:url :string :timeout_ms :number :as_text :string}
           :doc  {:url        "Absolute URL"
                  :timeout_ms "Read timeout (default 30000)"
                  :as_text    "Strip HTML to plain text (default true)"}}]
         (let [result (web/fetch url (cond-> {}
                                       (integer? timeout_ms) (assoc :timeout-ms timeout_ms)
                                       (some? as_text)       (assoc :as-text? (boolean as_text))))]
           [(if (r/ok? result) (:ok result) result)]))

   (web_search "Search the web via the configured IWebSearcher.
Default backend NullWebSearcher errors with config guidance — install
a real provider downstream (key retrieval via hive-di, not raw env)."
         [{:keys [query max_results allowed_domains blocked_domains]
           :type {:query :string :max_results :number
                  :allowed_domains :string :blocked_domains :string}
           :doc  {:query "Search query"
                  :max_results "Max results (default 10)"
                  :allowed_domains "Restrict to these hosts"
                  :blocked_domains "Exclude these hosts"}}]
         (let [result (web/search query (cond-> {}
                                          (integer? max_results)        (assoc :max-results max_results)
                                          (sequential? allowed_domains) (assoc :allowed-domains allowed_domains)
                                          (sequential? blocked_domains) (assoc :blocked-domains blocked_domains)))]
           [(if (r/ok? result) (:ok result) result)]))))
