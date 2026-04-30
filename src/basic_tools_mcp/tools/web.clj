(ns basic-tools-mcp.tools.web
  (:require [basic-tools-mcp.web.api :as web]
            [clojure.string :as str]
            [hive-dsl.result :as r]))

(declare handle-web-fetch handle-web-search web-fetch-tool-def web-search-tool-def)

(defn handle-web-fetch
  "Fetch a URL via the configured IWebFetcher (default: java.net.http)."
  [{:keys [url timeout_ms as_text]}]
  (cond
    (or (not (string? url)) (str/blank? url))
    {:content [{:type "text" :text "url is required"}] :isError true}

    :else
    (let [opts   (cond-> {}
                   (integer? timeout_ms)  (assoc :timeout-ms timeout_ms)
                   (some? as_text)        (assoc :as-text? (boolean as_text)))
          result (web/fetch url opts)]
      (if (r/ok? result)
        (let [{:keys [status body url duration-ms bytes content-type]} (:ok result)]
          {:content [{:type "text"
                      :text (str "HTTP " status " — " url
                                 " (" bytes "B, " duration-ms "ms, " content-type ")\n\n"
                                 body)}]})
        {:content [{:type "text" :text (or (:message result) (pr-str result))}]
         :isError true}))))

(defn handle-web-search
  "Search via the configured IWebSearcher.
   Default backend (NullWebSearcher) returns guidance to install a real provider."
  [{:keys [query max_results allowed_domains blocked_domains]}]
  (cond
    (or (not (string? query)) (str/blank? query))
    {:content [{:type "text" :text "query is required"}] :isError true}

    :else
    (let [opts   (cond-> {}
                   (integer? max_results)        (assoc :max-results max_results)
                   (sequential? allowed_domains) (assoc :allowed-domains allowed_domains)
                   (sequential? blocked_domains) (assoc :blocked-domains blocked_domains))
          result (web/search query opts)]
      (if (r/ok? result)
        {:content [{:type "text" :text (pr-str (:ok result))}]}
        {:content [{:type "text" :text (or (:message result) (pr-str result))}]
         :isError true}))))

(defn web-fetch-tool-def []
  {:name        "web_fetch"
   :description "Fetch content from a URL. Returns plain text by default
(HTML auto-stripped). Use as_text=false for raw bytes/JSON.

Backed by IWebFetcher. Default JVM HttpClient; downstream can swap a
jsoup or curl-based implementation via web-core/set-default-fetcher!.

Errors return :web/fetch-failed with cause."
   :inputSchema {:type       "object"
                 :properties {:url        {:type "string"
                                           :description "Absolute URL (http/https)"}
                              :timeout_ms {:type "integer"
                                           :description "Read timeout (default 30000)"}
                              :as_text    {:type "boolean"
                                           :description "Strip HTML to plain text (default true)"}}
                 :required   ["url"]}})

(defn web-search-tool-def []
  {:name        "web_search"
   :description "Search the web via the configured IWebSearcher.

Default backend is NullWebSearcher — returns a configuration error pointing
to web-core/set-default-searcher!. Real providers (Brave, Tavily, SearXNG,
Anthropic beta web_search) live downstream where API keys are wired."
   :inputSchema {:type       "object"
                 :properties {:query           {:type "string" :description "Search query"}
                              :max_results     {:type "integer" :description "Max results (default 10)"}
                              :allowed_domains {:type        "array"
                                                :items       {:type "string"}
                                                :description "Restrict to these hosts"}
                              :blocked_domains {:type        "array"
                                                :items       {:type "string"}
                                                :description "Exclude these hosts"}}
                 :required   ["query"]}})
