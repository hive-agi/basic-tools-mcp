(ns basic-tools-mcp.web-collect-test
  "Pure request shaping in web.collect: default headers, caller override,
   and the one-shot HTTP/1.1 retry on an empty response."
  (:require [basic-tools-mcp.web.collect :as collect]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r])
  (:import [java.net.http HttpClient$Version HttpRequest]))

(defn- ua-values
  [^HttpRequest req]
  (vec (.allValues (.headers req) "User-Agent")))

(deftest default-user-agent-is-browser-shaped
  (is (str/starts-with? collect/default-user-agent "Mozilla/5.0"))
  (is (not (str/includes? collect/default-user-agent "hive-agent"))))

(deftest request-headers-defaults
  (testing "no caller headers -> browser UA constant + Accept"
    (is (= collect/default-headers (collect/request-headers nil)))
    (is (= collect/default-user-agent
           (get (collect/request-headers {}) "User-Agent")))))

(deftest request-headers-caller-override-wins
  (doseq [k ["User-Agent" "user-agent" :user-agent :User-Agent]]
    (testing (str "override key " (pr-str k))
      (let [hs  (collect/request-headers {k "custom/1.0"})
            uas (keep (fn [[n v]] (when (= "user-agent" (str/lower-case n)) v)) hs)]
        (is (= ["custom/1.0"] uas))
        (is (= (get collect/default-headers "Accept") (get hs "Accept"))))))
  (testing "unrelated caller headers are added alongside defaults"
    (let [hs (collect/request-headers {:x-trace "abc"})]
      (is (= "abc" (get hs "x-trace")))
      (is (= collect/default-user-agent (get hs "User-Agent"))))))

(deftest build-request-sends-exactly-one-user-agent
  (let [build #'collect/build-request]
    (testing "default"
      (is (= [collect/default-user-agent]
             (ua-values (build "https://example.org" 1000 nil)))))
    (testing "explicit override replaces, never duplicates"
      (is (= ["custom/1.0"]
             (ua-values (build "https://example.org" 1000 {"user-agent" "custom/1.0"})))))))

(def ^:private no-bytes
  (r/err :weave/exception {:name "web/fetch"
                           :message "HTTP/1.1 header parser received no bytes"
                           :class "class java.io.IOException"}))

(deftest empty-response-is-retried-once-on-http11
  (let [calls (atom [])
        send  (fn [version] (swap! calls conj version)
                (if (nil? version) no-bytes (r/ok :response)))]
    (is (= (r/ok :response) (collect/send-with-http11-retry send)))
    (is (= [nil HttpClient$Version/HTTP_1_1] @calls))))

(deftest retry-happens-at-most-once
  (let [calls (atom [])
        send  (fn [version] (swap! calls conj version) no-bytes)]
    (is (= no-bytes (collect/send-with-http11-retry send)))
    (is (= 2 (count @calls)))))

(deftest other-failures-and-successes-are-not-retried
  (doseq [result [(r/ok :response)
                  (r/err :weave/timeout {:name "web/fetch" :timeout-ms 1})
                  (r/err :weave/exception {:message "Connection refused"
                                           :class "class java.net.ConnectException"})]]
    (let [calls (atom 0)]
      (is (= result (collect/send-with-http11-retry (fn [_] (swap! calls inc) result))))
      (is (= 1 @calls)))))

(deftest build-request-pins-version-only-when-asked
  (let [build #'collect/build-request]
    (is (.isEmpty (.version ^HttpRequest (build "https://example.org" 1000 nil))))
    (is (= HttpClient$Version/HTTP_1_1
           (.get (.version ^HttpRequest (build "https://example.org" 1000 nil
                                               HttpClient$Version/HTTP_1_1)))))))
