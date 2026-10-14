(ns basic-tools-mcp.web-collect-test
  "Pure request shaping in web.collect: default headers and caller override."
  (:require [basic-tools-mcp.web.collect :as collect]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.net.http HttpRequest]))

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
