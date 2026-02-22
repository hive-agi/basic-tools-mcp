(ns basic-tools-mcp.tools-test
  "Integration tests for MCP tool handlers.

   Tests the wrap command end-to-end through handle-clojure."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [basic-tools-mcp.tools :as tools]
            [basic-tools-mcp.file-core :as fc]
            [hive-dsl.result :as r])
  (:import [java.io File]))

;; =============================================================================
;; Temp dir fixture
;; =============================================================================

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (File/createTempFile "tools-test-" "")]
    (.delete dir)
    (.mkdirs dir)
    (try
      (binding [*test-dir* (.getAbsolutePath dir)]
        (f))
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(use-fixtures :each temp-dir-fixture)

;; =============================================================================
;; wrap command — integration tests
;; =============================================================================

(deftest wrap-command-success-test
  (testing "wraps a form at target line and writes back"
    (let [path    (str *test-dir* "/test.clj")
          source  "(ns example)\n\n(defn foo [x]\n  (+ x 1))\n\n(defn bar [y]\n  (* y 2))"
          _       (spit path source)
          result  (tools/handle-clojure {:command "wrap"
                                         :file_path path
                                         :line 3
                                         :template "(do %s)"})]
      (is (not (:isError result)))
      (let [written (slurp path)]
        (is (re-find #"\(do \(defn foo" written))
        (is (re-find #"\(ns example\)" written))
        (is (re-find #"\(defn bar" written))))))

(deftest wrap-command-missing-params-test
  (testing "missing file_path returns error"
    (let [result (tools/handle-clojure {:command "wrap"
                                        :line 1
                                        :template "(do %s)"})]
      (is (:isError result))))

  (testing "missing line returns error"
    (let [result (tools/handle-clojure {:command "wrap"
                                        :file_path "/tmp/test.clj"
                                        :template "(do %s)"})]
      (is (:isError result))))

  (testing "missing template returns error"
    (let [result (tools/handle-clojure {:command "wrap"
                                        :file_path "/tmp/test.clj"
                                        :line 1})]
      (is (:isError result))))

  (testing "template without %s returns error"
    (let [result (tools/handle-clojure {:command "wrap"
                                        :file_path "/tmp/test.clj"
                                        :line 1
                                        :template "(do something)"})]
      (is (:isError result)))))

(deftest wrap-command-nonexistent-file-test
  (testing "wrapping a nonexistent file returns error"
    (let [result (tools/handle-clojure {:command "wrap"
                                        :file_path "/tmp/definitely-does-not-exist-12345.clj"
                                        :line 1
                                        :template "(do %s)"})]
      (is (:isError result)))))

;; =============================================================================
;; validated-write-file — integration tests
;; =============================================================================

(deftest validated-write-file-rejects-unbalanced-test
  (testing "rejects unbalanced Clojure content"
    (let [path   (str *test-dir* "/bad.clj")
          result (fc/validated-write-file {:file_path path
                                           :content "(defn f [x"})]
      (is (r/err? result))
      (is (= :io/unbalanced-delimiters (:error result)))))

  (testing "accepts balanced Clojure content"
    (let [path   (str *test-dir* "/good.clj")
          result (fc/validated-write-file {:file_path path
                                           :content "(defn f [x] x)"})]
      (is (r/ok? result))))

  (testing "allows any content for non-Clojure files"
    (let [path   (str *test-dir* "/notes.txt")
          result (fc/validated-write-file {:file_path path
                                           :content "(defn f [x"})]
      (is (r/ok? result)))))
