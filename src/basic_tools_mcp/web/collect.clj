(ns basic-tools-mcp.web.collect
  "CPPB Collect — IWebFetcher protocol + JvmHttpFetcher backend.

   Side-effecting reads (HTTP) live here. Pure text transform happens
   downstream in web.text.

   DIP: callers depend on IWebFetcher, never on java.net.http directly.
   Default backend uses the JDK 11+ HttpClient — zero added deps."
  (:require [basic-tools-mcp.web.text :as text]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-weave.gate :as gate]
            [hive-weave.pool :as pool]
            [hive-weave.safe :as weave])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Version HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]))

;; =============================================================================
;; Protocol
;; =============================================================================

(defprotocol IWebFetcher
  "URL → fetched content. All ops return Result."
  (-fetcher-id [this])
  (-fetch [this url opts]
    "Opts: :timeout-ms (default 30000), :headers, :as-text? (default true).
     Returns Result<{:status :body :url :content-type :duration-ms :bytes}>."))

(defrecord GatedFetcher [fetcher admission]
  IWebFetcher
  (-fetcher-id [_] (-fetcher-id fetcher))
  (-fetch [_ url opts]
    (let [result (gate/gate-run admission #(-fetch fetcher url opts))]
      (if (r/ok? result)
        (:ok result)
        (r/err :web/fetch-failed
               {:message "web fetch admission refused"
                :url url
                :cause (:error result)})))))

(defn gated-fetcher
  "Wrap any IWebFetcher with a swappable hive-weave admission gate.
   The delegate is never called when a permit cannot be acquired."
  [fetcher admission]
  (->GatedFetcher fetcher admission))

;; =============================================================================
;; JvmHttpFetcher — default backend (java.net.http)
;; =============================================================================

(def ^:private ^HttpClient default-http-client
  (.. (HttpClient/newBuilder)
      (connectTimeout (Duration/ofSeconds 10))
      build))

(def default-user-agent
  "Browser-shaped User-Agent sent when the caller supplies none.
   Some origins (e.g. www.planalto.gov.br) drop the connection outright
   for a bot-like UA such as \"hive-agent/0.1\", which surfaces as
   \"HTTP/1.1 header parser received no bytes\"."
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

(def default-headers
  "Headers every request carries unless the caller overrides them."
  {"User-Agent" default-user-agent
   "Accept"     "text/html,text/plain,application/json,*/*;q=0.8"})

(defn request-headers
  "Pure: merge caller `headers` over `default-headers`. Header names are
   case-insensitive, so a caller key in any case (string or keyword)
   replaces the default of the same name instead of duplicating it."
  [headers]
  (let [caller   (into {} (map (fn [[k v]] [(name k) (str v)])) headers)
        overrode (into #{} (map str/lower-case) (keys caller))]
    (merge (into {} (remove (fn [[k _]] (overrode (str/lower-case k))))
                 default-headers)
           caller)))

(defn- build-request
  (^HttpRequest [^String url ^long timeout-ms headers]
   (build-request url timeout-ms headers nil))
  (^HttpRequest [^String url ^long timeout-ms headers version]
   (let [b (.. (HttpRequest/newBuilder (URI/create url))
               (timeout (Duration/ofMillis timeout-ms)))]
     (when version
       (.version b ^HttpClient$Version version))
     (doseq [[k v] (request-headers headers)]
       (.setHeader b ^String k ^String v))
     (.build b))))

(def empty-response-pattern
  "Messages the JDK client raises when the origin closed the connection
   without sending a response: an HTTP/2 upgrade or ALPN negotiation some
   origins (e.g. www.planalto.gov.br) silently drop."
  #"(?i)received no bytes|EOF reached while reading|connection reset|GOAWAY|stream was reset|RST_STREAM")

(defn empty-response-error?
  "True when a failed fetch RESULT (a safe-future err) means the origin sent
   no bytes, which is worth one retry pinned to HTTP/1.1."
  [result]
  (and (not (r/ok? result))
       (= :weave/exception (:error result))
       (boolean (re-find empty-response-pattern (str (:message result) " " (:class result))))))

(defn- looks-like-html?
  [^String content-type ^String body]
  (or (str/includes? (str/lower-case (or content-type "")) "html")
      (str/starts-with? (str/triml (or body "")) "<")))

(defn- shape-response
  [resp url start-ms as-text?]
  (let [body (.body resp)
        ct   (or (-> resp .headers (.firstValue "content-type") (.orElse "")) "")
        out  (if (and as-text? (looks-like-html? ct body))
               (text/html->text body)
               body)]
    {:status       (.statusCode resp)
     :body         out
     :url          url
     :content-type ct
     :duration-ms  (- (System/currentTimeMillis) start-ms)
     :bytes        (count (or body ""))}))

(defonce ^:private fetch-pool
  (pool/make-pool {:name "web-fetch" :size 8 :queue-capacity 8
                   :rejection :abort}))

(defonce ^:private fetch-admission
  (gate/gate {:name "web-fetch" :permits 8 :timeout-ms 100}))

(defn- send-once
  "One bounded send of URL; VERSION nil lets the client negotiate."
  [^HttpClient client url timeout-ms headers version]
  (let [req (build-request url timeout-ms headers version)]
    (weave/safe-future-call
     {:timeout-ms (+ timeout-ms 5000) :name "web/fetch" :pool fetch-pool}
     #(.send client req (HttpResponse$BodyHandlers/ofString)))))

(defn send-with-http11-retry
  "SEND (fn [version] -> Result) once with the client's negotiated version
   and, when that fails with an empty response (empty-response-error?), once
   more pinned to HTTP/1.1. Returns the last Result."
  [send]
  (let [first-try (send nil)]
    (if (empty-response-error? first-try)
      (send HttpClient$Version/HTTP_1_1)
      first-try)))

(defrecord JvmHttpFetcher [^HttpClient client]
  IWebFetcher
  (-fetcher-id [_] :jvm-http)
  (-fetch [_ url {:keys [timeout-ms headers as-text?]
                  :or   {timeout-ms 30000 as-text? true}}]
    (let [start    (System/currentTimeMillis)
          fut-resp (send-with-http11-retry
                    #(send-once client url timeout-ms headers %))]
      (if (r/ok? fut-resp)
        (r/ok (shape-response (:ok fut-resp) url start as-text?))
        (r/err :web/fetch-failed
               {:message (or (:message fut-resp) "fetch failed")
                :url     url
                :cause   (:error fut-resp)})))))

(defn make-jvm-fetcher
  "Construct the default admission-bound HTTP fetcher, or supply a client
   without changing the IWebFetcher contract."
  ([]                   (make-jvm-fetcher default-http-client))
  ([^HttpClient client] (gated-fetcher (->JvmHttpFetcher client) fetch-admission)))
