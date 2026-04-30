(ns basic-tools-mcp.web.collect
  "CPPB Collect — IWebFetcher protocol + JvmHttpFetcher backend.

   Side-effecting reads (HTTP) live here. Pure text transform happens
   downstream in web.text.

   DIP: callers depend on IWebFetcher, never on java.net.http directly.
   Default backend uses the JDK 11+ HttpClient — zero added deps."
  (:require [basic-tools-mcp.web.text :as text]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-weave.safe :as weave])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
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

;; =============================================================================
;; JvmHttpFetcher — default backend (java.net.http)
;; =============================================================================

(def ^:private ^HttpClient default-http-client
  (.. (HttpClient/newBuilder)
      (connectTimeout (Duration/ofSeconds 10))
      build))

(defn- build-request
  ^HttpRequest [^String url ^long timeout-ms headers]
  (let [b (.. (HttpRequest/newBuilder (URI/create url))
              (timeout (Duration/ofMillis timeout-ms))
              (header "User-Agent" "hive-agent/0.1 (basic-tools-mcp)")
              (header "Accept"     "text/html,text/plain,application/json,*/*;q=0.8"))]
    (doseq [[k v] headers]
      (.header b (name k) (str v)))
    (.build b)))

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

(defrecord JvmHttpFetcher [^HttpClient client]
  IWebFetcher
  (-fetcher-id [_] :jvm-http)
  (-fetch [_ url {:keys [timeout-ms headers as-text?]
                  :or   {timeout-ms 30000 as-text? true}}]
    (let [start    (System/currentTimeMillis)
          req      (build-request url timeout-ms headers)
          fut-resp (weave/safe-future-call
                    {:timeout-ms (+ timeout-ms 5000) :name "web/fetch"}
                    #(.send client req (HttpResponse$BodyHandlers/ofString)))]
      (if (r/ok? fut-resp)
        (r/ok (shape-response (:ok fut-resp) url start as-text?))
        (r/err :web/fetch-failed
               {:message (or (:message fut-resp) "fetch failed")
                :url     url
                :cause   (:error fut-resp)})))))

(defn make-jvm-fetcher
  ([]                     (->JvmHttpFetcher default-http-client))
  ([^HttpClient client]   (->JvmHttpFetcher client)))
