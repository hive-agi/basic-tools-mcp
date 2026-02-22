(ns basic-tools-mcp.structural-test
  "Unit tests for structural editing operations.

   Tests: balanced?, locate-top-level-form, wrap-form-str, wrap-in-source.
   All tests use inline source strings — no file IO."
  (:require [clojure.test :refer [deftest testing is]]
            [basic-tools-mcp.structural :as structural]
            [hive-dsl.result :as r]))

;; =============================================================================
;; balanced?
;; =============================================================================

(deftest balanced?-test
  (testing "valid forms return true"
    (is (true? (structural/balanced? "(defn f [x] x)")))
    (is (true? (structural/balanced? "(let [a 1\n      b 2]\n  (+ a b))")))
    (is (true? (structural/balanced? "{:a 1 :b [2 3]}")))
    (is (true? (structural/balanced? "(ns foo)\n\n(defn bar [] :ok)"))))

  (testing "unbalanced forms return false"
    (is (false? (structural/balanced? "(defn f [x] x")))
    (is (false? (structural/balanced? "(let [a 1")))
    (is (false? (structural/balanced? "{:a [1 2}")))
    (is (false? (structural/balanced? "((("))))

  (testing "empty and whitespace"
    (is (true? (structural/balanced? "")))
    (is (true? (structural/balanced? "  \n  ")))))

;; =============================================================================
;; locate-top-level-form
;; =============================================================================

(def multi-form-source
  "(ns example)

(defn foo [x]
  (+ x 1))

(defn bar [y]
  (* y 2))")

(deftest locate-top-level-form-test
  (testing "finds ns form at line 1"
    (let [result (structural/locate-top-level-form multi-form-source 1)]
      (is (some? result))
      (is (= 1 (:start-line result)))))

  (testing "finds foo at line 3"
    (let [result (structural/locate-top-level-form multi-form-source 3)]
      (is (some? result))
      (is (= 3 (:start-line result)))))

  (testing "finds foo at line 4 (inside body)"
    (let [result (structural/locate-top-level-form multi-form-source 4)]
      (is (some? result))
      (is (= 3 (:start-line result)))))

  (testing "finds bar at line 6"
    (let [result (structural/locate-top-level-form multi-form-source 6)]
      (is (some? result))
      (is (= 6 (:start-line result)))))

  (testing "returns nil for line beyond source"
    (is (nil? (structural/locate-top-level-form multi-form-source 100)))))

;; =============================================================================
;; wrap-form-str
;; =============================================================================

(deftest wrap-form-str-test
  (testing "simple wrap with or-guard template"
    (let [result (structural/wrap-form-str "(let [x 1] x)" "(or (guard) %s)")]
      (is (r/ok? result))
      (is (= "(or (guard) (let [x 1] x))" (:ok result)))))

  (testing "wrap with when-let template"
    (let [result (structural/wrap-form-str "(+ 1 2)" "(when-let [v %s] v)")]
      (is (r/ok? result))
      (is (= "(when-let [v (+ 1 2)] v)" (:ok result)))))

  (testing "invalid template returns error"
    (let [result (structural/wrap-form-str "(+ 1 2)" "(or (guard %s")]
      (is (r/err? result)))))

;; =============================================================================
;; wrap-in-source
;; =============================================================================

(deftest wrap-in-source-test
  (testing "wraps form at target line in multi-form source"
    (let [result (structural/wrap-in-source multi-form-source 3 "(do %s)")]
      (is (r/ok? result))
      (is (structural/balanced? (:ok result)))
      ;; The wrapped form should contain (do (defn foo ...))
      (is (re-find #"\(do \(defn foo" (:ok result)))))

  (testing "wraps single-line ns form"
    (let [result (structural/wrap-in-source multi-form-source 1 "(comment %s)")]
      (is (r/ok? result))
      (is (structural/balanced? (:ok result)))))

  (testing "line with no form returns error"
    (let [result (structural/wrap-in-source multi-form-source 100 "(do %s)")]
      (is (r/err? result))))

  (testing "result preserves other forms"
    (let [result (structural/wrap-in-source multi-form-source 3 "(do %s)")]
      ;; ns and bar should still be present
      (is (re-find #"\(ns example\)" (:ok result)))
      (is (re-find #"\(defn bar" (:ok result))))))
