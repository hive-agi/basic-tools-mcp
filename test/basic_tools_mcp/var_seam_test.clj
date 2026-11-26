(ns basic-tools-mcp.var-seam-test
  "Invariant gate for the `basic-tools-mcp.tools` facade (epic
   20260821211613-0c0e618f).

   A `(def x owner/x)` re-export stores the fn OBJECT, so a reload of the
   owner never reaches callers entering through the facade. Each re-export
   is now a delegating defn; this namespace rebinds a leaf and asserts the
   change is visible through the facade, keeps a control showing the frozen
   shape is stale, and fails if a fn-valued frozen alias returns to src/."
  (:require [basic-tools-mcp.tools :as tools]
            [basic-tools-mcp.tools.clojure :as tclj]
            [basic-tools-mcp.tools.file :as tfile]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- with-rebound-leaf
  "alter-var-root (not with-redefs): the rebinding must reach code that
   resolves the var LATER, which is what a hot reload does."
  [leaf-var replacement f]
  (let [original @leaf-var]
    (try
      (alter-var-root leaf-var (constantly replacement))
      (f)
      (finally (alter-var-root leaf-var (constantly original))))))

(deftest facade-sees-a-rebound-leaf-test
  (testing "a reload of tools.file/handle-grep is visible through tools/handle-grep"
    (with-rebound-leaf #'tfile/handle-grep (fn [& _] ::rebound)
      (fn [] (is (= ::rebound (tools/handle-grep {:pattern "x"}))))))
  (testing "a reload of tools.clojure/tool-def is visible through BOTH aliases"
    (with-rebound-leaf #'tclj/tool-def (fn [] {:name ::rebound})
      (fn []
        (is (= ::rebound (:name (tools/tool-def))))
        (is (= ::rebound (:name (tools/clojure-tool-def))))
        (is (= ::rebound (:name (tclj/clojure-tool-def)))
            "the in-ns alias clojure-tool-def -> tool-def follows the var too")))))

(deftest frozen-copy-is-stale-control-test
  (testing "CONTROL: a captured fn object does not see the rebinding"
    (let [frozen tfile/handle-grep]
      (with-rebound-leaf #'tfile/handle-grep (fn [& _] ::rebound)
        (fn []
          (is (= ::rebound (tools/handle-grep {:pattern "x"})))
          (is (not (identical? @#'tfile/handle-grep frozen))
              "the frozen object is the OLD fn: the staleness the seam removes"))))))

(deftest tool-def-handlers-stay-raw-fns-test
  (testing "tool-def :handler entries are still fn? (hive-addon validate-addon)"
    (doseq [d (tools/file-tool-defs)]
      (is (fn? (:handler d)) (str (:name d) " :handler is a raw fn")))))

;; -----------------------------------------------------------------------------
;; Census: no fn-valued `(def name owner/sym)` or `(def name sym)` in src/
;; -----------------------------------------------------------------------------

(def ^:private frozen-alias-pattern
  #"(?m)^\(def\s+([^\s\)\^]+)\s+([a-zA-Z][\w.\-]*/[^\s\)]+|[a-z][\w\-!?*<>]*)\s*\)")

(defn- src-files []
  (->> (file-seq (io/file "src"))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))))

(defn- file-ns [f]
  (some->> (slurp f) (re-find #"\(ns\s+([^\s\)]+)") second symbol))

(deftest no-fn-valued-frozen-alias-in-src-test
  (let [files (src-files)]
    (is (seq files) "src/ was found (non-vacuity)")
    (let [offenders
          (for [f files
                :let [ns-sym (file-ns f)]
                :when ns-sym
                [_ n _] (re-seq frozen-alias-pattern (slurp f))
                :let [_ (require ns-sym)
                      v (ns-resolve ns-sym (symbol n))]
                :when (and v (fn? @v))]
            (str ns-sym "/" n))]
      (is (= [] (vec (sort offenders)))
          "these defs capture a fn object instead of delegating through the var"))))
