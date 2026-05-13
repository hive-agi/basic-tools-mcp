(ns basic-tools-mcp.edit-core-trifecta-test
  "Trifecta (golden + property + mutation) for the LLM tool-call XML-fragment
   sentinel in `basic-tools-mcp.edit-core`.

   Covers:
   - `detect-tool-call-fragment` — pure detector, full trifecta.
   - `find-all-tool-call-fragments` — vector-returning detector used by callers
     that need every distinct match (not just the first).
   - `apply-edit` integration — verifies the `:tool-call-fragment-detected`
     error variant with `:tags-found [...]` is returned when `new-string` contains
     leaked markup."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-dsl.result :as r]
            [basic-tools-mcp.edit-core :as ec]))

;; =============================================================================
;; Fixture strings (kept as vars so the test source stays parser-friendly)
;; =============================================================================

(def lt (str (char 60)))
(def gt (str (char 62)))

(defn tag
  "Build an XML-ish tag string at runtime, e.g. (tag \"invoke\") -> \"<invoke\"."
  ([name] (str lt name))
  ([name body] (str lt name gt body lt "/" name gt)))

(def invoke-bare         (tag "invoke"))
(def invoke-attr         (str (tag "invoke") " name=\"x\"" gt))
(def invoke-self-closed  (str (tag "invoke") "/" gt))
(def parameter-bare      (tag "parameter"))
(def parameter-attr      (str (tag "parameter") " name=\"foo\"" gt))
(def new-body-bare       (tag "new-body"))
(def new-body-closed     (tag "new-body" "x"))
(def function-calls-tag  (str (tag "function_calls") gt))
(def antml-invoke        (tag "antml:invoke"))
(def antml-parameter     (tag "antml:parameter"))
(def two-tags            (str (tag "parameter") " foo=\"1\"" gt (tag "invoke") gt))
(def embedded-in-source  (str "(defn f [] " (pr-str invoke-attr) ")"))

(def tag-fragments
  "Fragments suitable for generative testing — each has an explicit word
   boundary (whitespace, '>', '/', '\"') after the tag name so they trigger
   the detector regardless of surrounding characters in the embedded gen.
   Bare openers like '<invoke' are exercised by the golden cases only."
  [invoke-attr invoke-self-closed
   parameter-attr
   new-body-closed
   function-calls-tag
   (str antml-invoke gt)
   (str antml-parameter gt)])

(def gen-clean-clojure
  "Strings that should NEVER trigger the detector."
  (gen/elements ["(defn f [x] x)"
                 "(< 1 2)"
                 "(if (< x y) x y)"
                 "{:a 1 :b 2}"
                 "[1 2 3]"
                 ";; comment with < and > chars"
                 "(str \"hello world\")"
                 ""
                 "  \n  "
                 ;; angle wraps with names NOT in the whitelist
                 (str lt "foo" gt)
                 (str lt gt)]))

(def gen-dirty-fragment
  (gen/elements tag-fragments))

(def gen-dirty-embedded
  (gen/let [prefix gen/string-ascii
            t      gen-dirty-fragment
            suffix gen/string-ascii]
    (str prefix t suffix)))

;; =============================================================================
;; 1. detect-tool-call-fragment - full trifecta
;; =============================================================================

(deftrifecta detect-tool-call-fragment
  basic-tools-mcp.edit-core/detect-tool-call-fragment
  {:golden-path "test/golden/edit-core/detect-tool-call-fragment.edn"
   :cases       {:empty                       ""
                 :whitespace                  "  \n  "
                 :clean-clojure               "(defn f [x] x)"
                 :less-than-comparator        "(< 1 2)"
                 :angle-not-whitelisted       (str lt "foo" gt)
                 :empty-angles                (str lt gt)
                 :string-with-angle-brackets  (str "(str " (pr-str (str lt "world" gt)) ")")
                 :invoke-bare                 invoke-bare
                 :invoke-with-attr            invoke-attr
                 :invoke-self-closed          invoke-self-closed
                 :parameter-bare              parameter-bare
                 :parameter-with-attr         parameter-attr
                 :new-body-bare               new-body-bare
                 :new-body-closed             new-body-closed
                 :function-calls              function-calls-tag
                 :antml-invoke                antml-invoke
                 :antml-parameter             antml-parameter
                 :antml-deep-namespaced       (tag "antml:foo_bar")
                 :parameter-block-not-tag     (tag "parameter_block")
                 :invoker-not-tag             (tag "invoker")
                 :embedded-in-source          embedded-in-source
                 :two-tags-first-wins         two-tags
                 :nil-input                   nil
                 :keyword-input               :not-a-string}
   :gen         (gen/one-of [gen-clean-clojure
                             gen-dirty-fragment
                             gen-dirty-embedded
                             gen/string-ascii])
   :property-type :totality
   :mutations   [["always-nil"        (fn [_] nil)]
                 ["always-truthy"     (fn [_] "yes")]
                 ["only-invoke"       (fn [s] (when (and (string? s)
                                                         (re-find #"<invoke\b" s))
                                                "<invoke"))]
                 ["drops-antml-ns"    (fn [s] (when (string? s)
                                                (when-let [m (re-find #"<(invoke|parameter|new-body|function_calls)\b" s)]
                                                  (if (vector? m) (first m) m))))]
                 ["matches-any-angle" (fn [s] (when (and (string? s)
                                                         (re-find #"<\w+" s))
                                                "<tag"))]]})

;; =============================================================================
;; 2. detect-tool-call-fragment - extra invariants
;; =============================================================================

(defspec detect-result-is-nil-or-substring 200
  (prop/for-all [s gen/string-ascii]
    (let [out (ec/detect-tool-call-fragment s)]
      (or (nil? out)
          (and (string? out) (str/includes? s out))))))

(defspec detect-clean-clojure-is-nil 200
  (prop/for-all [s gen-clean-clojure]
    (nil? (ec/detect-tool-call-fragment s))))

(defspec detect-dirty-fragment-is-truthy 200
  (prop/for-all [s gen-dirty-fragment]
    (some? (ec/detect-tool-call-fragment s))))

(defspec detect-dirty-embedded-is-truthy 200
  (prop/for-all [s gen-dirty-embedded]
    (some? (ec/detect-tool-call-fragment s))))

(defspec detect-non-string-is-nil 200
  (prop/for-all [v (gen/one-of [(gen/return nil)
                                gen/keyword
                                gen/small-integer
                                (gen/return [])
                                (gen/return {})])]
    (nil? (ec/detect-tool-call-fragment v))))

(defspec detect-is-idempotent 200
  (prop/for-all [s gen/string-ascii]
    (= (ec/detect-tool-call-fragment s)
       (ec/detect-tool-call-fragment s))))

;; =============================================================================
;; 3. apply-edit integration - rejection path
;; =============================================================================

(def base-content
  "(ns demo)\n\n(defn f [x] x)\n")

(defn- err-tag
  "Extract the :error category from an apply-edit Result (or nil if ok).
   hive-dsl Result is a plain map: ok={:ok v}, err={:error cat ...extra}."
  [result]
  (when (r/err? result)
    (:error result)))

(deftest apply-edit-rejects-invoke
  (testing "new-string containing an <invoke> tag is rejected"
    (let [result (ec/apply-edit
                  {:content    base-content
                   :old-string "(defn f [x] x)"
                   :new-string (str "(defn f [x] " invoke-attr "x" lt "/invoke" gt ")")})]
      (is (r/err? result))
      (is (= :tool-call-fragment-detected (err-tag result)))
      (is (vector? (:tags-found result)))
      (is (seq (:tags-found result))))))

(deftest apply-edit-rejects-parameter
  (testing "new-string containing a <parameter> tag is rejected"
    (let [result (ec/apply-edit
                  {:content    base-content
                   :old-string "(defn f [x] x)"
                   :new-string (str "(defn f [x] " parameter-attr "x" lt "/parameter" gt ")")})]
      (is (r/err? result))
      (is (= :tool-call-fragment-detected (err-tag result)))
      (is (vector? (:tags-found result)))
      (is (seq (:tags-found result))))))

(deftest apply-edit-rejects-new-body
  (testing "new-string containing a <new-body> tag is rejected"
    (let [result (ec/apply-edit
                  {:content    base-content
                   :old-string "(defn f [x] x)"
                   :new-string new-body-closed})]
      (is (r/err? result))
      (is (= :tool-call-fragment-detected (err-tag result)))
      (is (vector? (:tags-found result)))
      (is (seq (:tags-found result))))))

(deftest apply-edit-rejects-function-calls
  (testing "new-string containing <function_calls> is rejected"
    (let [result (ec/apply-edit
                  {:content    base-content
                   :old-string "(defn f [x] x)"
                   :new-string (str function-calls-tag "\n(defn f [x] x)\n" lt "/function_calls" gt)})]
      (is (r/err? result))
      (is (= :tool-call-fragment-detected (err-tag result)))
      (is (vector? (:tags-found result)))
      (is (seq (:tags-found result))))))

(deftest apply-edit-allows-clean-edit
  (testing "clean replacement still works - sentinel does not over-reject"
    (let [result (ec/apply-edit
                  {:content    base-content
                   :old-string "(defn f [x] x)"
                   :new-string "(defn f [x] (inc x))"})]
      (is (r/ok? result))
      (is (str/includes? (:ok result) "(inc x)")))))

(deftest apply-edit-allows-less-than-operator
  (testing "Clojure < comparator is not flagged as a tool-call fragment"
    (let [result (ec/apply-edit
                  {:content    base-content
                   :old-string "(defn f [x] x)"
                   :new-string "(defn f [x] (if (< x 0) (- x) x))"})]
      (is (r/ok? result))
      (is (str/includes? (:ok result) "(< x 0)")))))

(deftest apply-edit-allows-angle-string-literal
  (testing "Angle brackets inside a string literal are NOT flagged
            (the detector is intentionally narrow; only specific tag names trigger)"
    (let [result (ec/apply-edit
                  {:content    base-content
                   :old-string "(defn f [x] x)"
                   :new-string (str "(defn f [x] (str " (pr-str (str lt "foo" gt)) " x))")})]
      (is (r/ok? result)))))
