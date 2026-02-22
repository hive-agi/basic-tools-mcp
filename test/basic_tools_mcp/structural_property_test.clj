(ns basic-tools-mcp.structural-property-test
  "Property-based tests for structural editing bounded context.

   Verifies algebraic properties that must hold for ALL valid inputs.
   Pure functions only — no IO, no fixtures needed.

   Value objects: source strings, templates, line numbers.
   Domain invariant: structural operations preserve delimiter balance.

   Pure predicates:
   - P1:  balanced? totality (never throws for any string)
   - P2:  balanced? idempotent (deterministic for same input)
   - P3:  balanced? returns boolean (output type invariant)

   wrap-form-str (Value Object -> Result):
   - P4:  Roundtrip — valid form + valid template => balanced output
   - P5:  Totality — never throws for any string combination
   - P6:  Structural — result always ok? xor err? (ADT completeness)

   wrap-in-source (Aggregate operation):
   - P7:  Totality — never throws for any source/line/template triple
   - P8:  ADT completeness — result always ok? xor err?
   - P9:  Balance preservation — balanced source + valid template => balanced output
   - P10: Form count preservation — wrap does not add/remove top-level forms

   locate-top-level-form:
   - P11: Totality — never throws for valid source + any line
   - P12: Monotonicity — found form's start-line <= target-line <= end-line"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-test.properties :as props]
            [hive-test.generators.core :as gen-core]
            [hive-dsl.result :as r]
            [basic-tools-mcp.structural :as structural]
            [rewrite-clj.zip :as z]))

;; =============================================================================
;; Domain Generators — Structural Editing Bounded Context
;; =============================================================================

(def gen-any-source
  "Generator for arbitrary strings (fuzz input — may be invalid Clojure)."
  gen/string-ascii)

(def gen-balanced-source
  "Generator for syntactically valid Clojure source strings.
   Value objects in the structural editing domain."
  (gen/elements ["(defn f [x] x)"
                 "(let [a 1] a)"
                 "(+ 1 2)"
                 "{:a 1 :b 2}"
                 "[1 2 3]"
                 "(ns foo)\n\n(defn bar [] :ok)"
                 "(defn multi [a b]\n  (let [c (+ a b)]\n    (* c 2)))"
                 "(if true :yes :no)"
                 "(cond true 1 false 2)"
                 "(do (println :a) (println :b))"
                 ";; comment\n(defn c [] :c)"
                 "(defprotocol P (method [this]))"
                 "(defrecord R [a b])"]))

(def gen-multi-form-source
  "Generator for source with multiple top-level forms (aggregate root scenario)."
  (gen/elements ["(ns example)\n\n(defn foo [x]\n  (+ x 1))\n\n(defn bar [y]\n  (* y 2))"
                 "(ns app)\n\n(def config {:port 8080})\n\n(defn start [] :ok)"
                 "(require '[clojure.string :as str])\n\n(defn greet [name]\n  (str \"Hello \" name))"]))

(def gen-valid-template
  "Generator for templates that produce balanced forms when applied to balanced input.
   Templates are value objects — %s is the substitution point."
  (gen/elements ["(do %s)"
                 "(comment %s)"
                 "(or nil %s)"
                 "(when true %s)"
                 "(let [_ nil] %s)"
                 "(try %s (catch Exception e nil))"
                 "(fn [] %s)"]))

(def gen-invalid-template
  "Generator for templates that produce unbalanced forms (negative testing)."
  (gen/elements ["(or (broken %s"
                 "((( %s"
                 "%s )))"
                 "(defn ["]))

(def gen-any-template
  "Generator for any template (valid or invalid)."
  (gen/one-of [gen-valid-template gen-invalid-template]))

(def gen-line-number
  "Generator for 1-based line numbers."
  (gen/choose 1 50))

(def gen-unbalanced-source
  "Generator for syntactically invalid Clojure source."
  (gen/elements ["(defn f [x"
                 "((("
                 "{:a [1 2}"
                 "(let [a 1"
                 "(-> x"
                 "\""]))

;; =============================================================================
;; P1 — Totality: balanced? never throws for any string input
;; =============================================================================

(props/defprop-total p1-balanced-totality
  structural/balanced? gen-any-source
  {:num-tests 200 :pred boolean?})

;; =============================================================================
;; P2 — Deterministic: balanced? returns same result for same input
;;       (Not idempotent — f: String->Boolean, domain != codomain)
;; =============================================================================

(defspec p2-balanced-deterministic 200
  (prop/for-all [s gen-any-source]
                (= (structural/balanced? s)
                   (structural/balanced? s))))

;; =============================================================================
;; P3 — Complement: balanced? true <=> source is parseable
;;       (balanced sources and unbalanced are mutually exclusive sets)
;; =============================================================================

(defspec p3-balanced-true-for-valid 200
  (prop/for-all [source gen-balanced-source]
                (true? (structural/balanced? source))))

(defspec p3b-balanced-false-for-invalid 200
  (prop/for-all [source gen-unbalanced-source]
                (false? (structural/balanced? source))))

;; =============================================================================
;; P4 — Roundtrip: valid form + valid template => balanced output
;; =============================================================================

(defspec p4-wrap-form-str-balance-preservation 200
  (prop/for-all [form gen-balanced-source
                 template gen-valid-template]
                (let [result (structural/wrap-form-str form template)]
                  (if (r/err? result)
                    true ;; vacuously true — template rejection is acceptable
                    (structural/balanced? (:ok result))))))

;; =============================================================================
;; P5 — Totality: wrap-form-str never throws for any string combination
;; =============================================================================

(defspec p5-wrap-form-str-totality 200
  (prop/for-all [form gen-any-source
                 template gen-any-source]
                (try
                  (let [result (structural/wrap-form-str form template)]
                    (or (r/ok? result) (r/err? result)))
                  (catch Exception _
                    false))))

;; =============================================================================
;; P6 — ADT completeness: wrap-form-str always returns :result xor :error
;; =============================================================================

(defspec p6-wrap-form-str-adt-completeness 200
  (prop/for-all [form gen-balanced-source
                 template gen-any-template]
                (let [result (structural/wrap-form-str form template)]
                  (not= (r/ok? result) (r/err? result)))))

;; =============================================================================
;; P7 — Totality: wrap-in-source never throws for any source/line/template
;; =============================================================================

(defspec p7-wrap-in-source-totality 100
  (prop/for-all [source gen-any-source
                 line gen-line-number
                 template gen-any-template]
                (try
                  (let [result (structural/wrap-in-source source line template)]
                    (or (r/ok? result) (r/err? result)))
                  (catch Exception _
                    false))))

;; =============================================================================
;; P8 — ADT completeness: wrap-in-source returns :result xor :error
;; =============================================================================

(defspec p8-wrap-in-source-adt-completeness 100
  (prop/for-all [source gen-balanced-source
                 template gen-any-template]
                (let [result (structural/wrap-in-source source 1 template)]
                  (not= (r/ok? result) (r/err? result)))))

;; =============================================================================
;; P9 — Balance preservation: balanced source + valid template => balanced output
;;       (Domain invariant: structural operations preserve delimiter integrity)
;; =============================================================================

(defspec p9-wrap-in-source-balance-preservation 100
  (prop/for-all [source gen-balanced-source
                 template gen-valid-template]
                (let [result (structural/wrap-in-source source 1 template)]
                  (if (r/err? result)
                    true ;; error path is acceptable (e.g., template mismatch)
                    (structural/balanced? (:ok result))))))

;; =============================================================================
;; P10 — Form count preservation: wrap replaces exactly one form,
;;        doesn't add or remove top-level forms
;; =============================================================================

(defn- count-top-level-forms
  "Count top-level forms in source using rewrite-clj."
  [source]
  (try
    (loop [loc (z/of-string source) n 0]
      (if (or (nil? loc) (z/end? loc))
        n
        (recur (z/right loc) (inc n))))
    (catch Exception _ 0)))

(defspec p10-wrap-preserves-form-count 100
  (prop/for-all [source gen-multi-form-source
                 template gen-valid-template]
                (let [before (count-top-level-forms source)
                      result (structural/wrap-in-source source 1 template)]
                  (if (r/err? result)
                    true
                    (= before (count-top-level-forms (:ok result)))))))

;; =============================================================================
;; P11 — Totality: locate-top-level-form never throws for valid source
;; =============================================================================

(defspec p11-locate-totality 200
  (prop/for-all [source gen-balanced-source
                 line gen-line-number]
                (try
                  (let [result (structural/locate-top-level-form source line)]
                    (or (nil? result) (map? result)))
                  (catch Exception _
                    false))))

;; =============================================================================
;; P12 — Monotonicity: found form's start-line <= target <= end-line
;;        (Locator respects line ordering invariant)
;; =============================================================================

(defspec p12-locate-bounds-monotonicity 100
  (prop/for-all [source gen-multi-form-source
                 line (gen/choose 1 7)]
                (let [result (structural/locate-top-level-form source line)]
                  (if (nil? result)
                    true ;; no form at that line is acceptable
                    (and (<= (:start-line result) line)
                         (<= line (:end-line result)))))))
