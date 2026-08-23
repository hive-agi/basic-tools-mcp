(ns basic-tools-mcp.file.search-test
  "The cap has to be VISIBLE, not merely applied.

   `capped-process-lines` returned a bare vector, so a caller handed exactly
   the budget could not tell a directory holding that many files from one
   holding a hundred times more — a truncated list read as a complete answer.
   hive-system's `lines!` reports `:truncated?`; these tests pin that it
   survives all the way out to what the MCP caller sees, on BOTH branches,
   and that it is absent when nothing was truncated."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-dsl.result :as r]
            [basic-tools-mcp.file.search :as search]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def ^:private max-files @#'search/max-files)
(def ^:private capped @#'search/capped)
(def ^:private truncation-notice @#'search/truncation-notice)

;;; =============================================================================
;;; Fixture — one tree over the cap, one comfortably under it
;;; =============================================================================

(def ^:private big-root (atom nil))
(def ^:private small-root (atom nil))

(defn- populate!
  [dir n]
  (fs/create-dirs dir)
  (doseq [i (range n)]
    (fs/create-file (fs/path dir (format "f%05d.txt" i))))
  (str dir))

(use-fixtures :once
  (fn [f]
    (let [big   (fs/create-temp-dir {:prefix "bt-search-big"})
          small (fs/create-temp-dir {:prefix "bt-search-small"})]
      (try
        (reset! big-root (populate! big (+ max-files 50)))
        (reset! small-root (populate! small 12))
        (f)
        (finally
          (fs/delete-tree big)
          (fs/delete-tree small))))))

;;; =============================================================================
;;; Promote — pure, no filesystem involved
;;; =============================================================================

(deftest capped-detects-the-cap-rather-than-merely-applying-it
  (testing "under budget: everything, and nothing claimed about truncation"
    (let [{:keys [matches truncated?]} (capped ["a" "b" "c"])]
      (is (= ["a" "b" "c"] matches))
      (is (false? truncated?))))
  (testing "exactly at budget is NOT truncated — the boundary case the old code got wrong"
    (let [{:keys [matches truncated?]} (capped (repeat max-files "x"))]
      (is (= max-files (count matches)))
      (is (false? truncated?))))
  (testing "one over budget is truncated, and the extra is not returned"
    (let [{:keys [matches truncated?]} (capped (repeat (inc max-files) "x"))]
      (is (= max-files (count matches)))
      (is (true? truncated?))
      "reading one MORE than the budget is what makes the cap detectable")))

(deftest the-notice-appears-only-when-something-was-truncated
  (is (= "a\nb" (truncation-notice "a\nb" false))
      "a complete answer must not be decorated — a notice that always fires says nothing")
  (let [noticed (truncation-notice "a\nb" true)]
    (is (str/starts-with? noticed "a\nb"))
    (is (str/includes? noticed (str max-files)))
    (is (str/includes? noticed "truncated"))))

;;; =============================================================================
;;; Boundary — the ripgrep path
;;; =============================================================================

(deftest rg-glob-reports-truncation-over-the-cap
  (let [{:keys [listing truncated?]} (search/rg-glob @big-root "**/*.txt")]
    (is (true? truncated?))
    (is (= max-files (count (str/split-lines listing)))
        "the cap is applied as well as reported")))

(deftest rg-glob-is-silent-about-truncation-under-the-cap
  ;; The negative control. Without it, a `:truncated? true` constant would
  ;; satisfy the test above.
  (let [{:keys [listing truncated?]} (search/rg-glob @small-root "**/*.txt")]
    (is (false? truncated?))
    (is (= 12 (count (str/split-lines listing))))
    (is (= (sort (str/split-lines listing)) (str/split-lines listing))
        "the listing is sorted")))

(deftest rg-glob-is-nil-when-nothing-matched
  (is (nil? (search/rg-glob @small-root "**/*.no-such-extension"))))

;;; =============================================================================
;;; Boundary — what the MCP caller actually receives
;;; =============================================================================

(deftest glob-files-tells-the-caller-the-list-is-partial
  (let [res (search/glob-files {:pattern "**/*.txt"
                                :path @big-root
                                :_caller_cwd @big-root})]
    (is (r/ok? res))
    (is (str/includes? (:ok res) "truncated")
        "otherwise the agent reads a capped list as the whole directory")
    (is (str/includes? (:ok res) (str max-files)))))

(deftest glob-files-says-nothing-about-truncation-when-it-did-not-truncate
  (let [res (search/glob-files {:pattern "**/*.txt"
                                :path @small-root
                                :_caller_cwd @small-root})]
    (is (r/ok? res))
    (is (not (str/includes? (:ok res) "truncated")))
    (is (= 12 (count (str/split-lines (:ok res)))))))
