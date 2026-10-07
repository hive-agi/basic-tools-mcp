(ns basic-tools-mcp.web-gate-trifecta-test
  "Port-backed admission contract: rejected requests never reach the fetcher."
  (:require [basic-tools-mcp.web.collect :as collect]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-dsl.result :as r]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-weave.gate :as gate]))

(defn admission-sample
  "Run one fetch against a stub port and report only stable observable facts."
  [admit?]
  (let [calls (atom 0)
        stub (reify collect/IWebFetcher
               (-fetcher-id [_] :stub)
               (-fetch [_ _ _]
                 (swap! calls inc)
                 (r/ok {:body "stub"})))
        fetcher (collect/gated-fetcher
                 stub (gate/gate {:permits (if admit? 1 0)
                                  :timeout-ms 0 :name "test-web-fetch"}))
        result (collect/-fetch fetcher "https://example.test/" {})]
    {:id (collect/-fetcher-id fetcher)
     :calls @calls
     :ok (when (r/ok? result) (:ok result))
     :error (:error result)
     :cause (get-in result [:cause])}))

(deftrifecta gated-fetcher-contract
  basic-tools-mcp.web-gate-trifecta-test/admission-sample
  {:golden-path "test/golden/web-gate/admission.edn"
   :cases {:admitted true :refused false}
   :gen gen/boolean
   :property-type :totality
   :mutations [["never-delegates" (fn [_] {:id :stub :calls 0})]
               ["always-delegates" (fn [_] {:id :stub :calls 1})]]})

(deftest concurrent-admission-rejects-before-invoking-stub
  (let [entered (promise)
        release (promise)
        calls (atom 0)
        stub (reify collect/IWebFetcher
               (-fetcher-id [_] :stub)
               (-fetch [_ _ _]
                 (swap! calls inc)
                 (deliver entered true)
                 (deref release 1000 nil)
                 (r/ok {:body "first"})))
        fetcher (collect/gated-fetcher
                 stub (gate/gate {:permits 1 :timeout-ms 10 :name "one"}))
        first-call (future (collect/-fetch fetcher "https://example.test/" {}))]
    (try
      (is (true? (deref entered 1000 false)))
      (let [rejected (collect/-fetch fetcher "https://example.test/" {})]
        (is (= :web/fetch-failed (:error rejected)))
        (is (= :gate/timeout (:cause rejected)))
        (is (= 1 @calls)))
      (finally (deliver release true)))
    (is (r/ok? (deref first-call 1000 nil)))
    (is (r/ok? (collect/-fetch fetcher "https://example.test/" {})))
    (is (= 2 @calls))))
