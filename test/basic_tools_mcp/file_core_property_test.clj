(ns basic-tools-mcp.file-core-property-test
  "Property-based tests for file-core operations.

   Properties tested:
   - Totality: functions never throw, always return Result ADT
   - Roundtrip: write then read recovers content
   - Idempotent: reading same file twice yields same result
   - Complement: ok?/err? are mutually exclusive on file ops"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-test.properties :as props]
            [hive-test.generators.core :as gen-core]
            [hive-dsl.result :as r]
            [basic-tools-mcp.file-core :as fc])
  (:import [java.io File]))

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-printable-content
  "Non-empty printable strings (file content)."
  (gen/such-that (complement clojure.string/blank?)
                 gen/string-ascii
                 100))

(def gen-filename
  "Valid filenames (alphanumeric, no slashes)."
  (gen/fmap #(str "test-" % ".txt") gen-core/gen-non-blank-string))

(def gen-offset (gen/choose 0 100))
(def gen-limit (gen/choose 1 2000))

(def gen-glob-pattern
  (gen/elements ["*.txt" "*.clj" "**/*.txt" "test-*" "*.md"]))

(def gen-grep-pattern
  (gen/elements ["defn" "test" "TODO" "FIXME" "require" "ns "]))

(defn- result? [x] (or (r/ok? x) (r/err? x)))

;; =============================================================================
;; Temp dir fixture
;; =============================================================================

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (File/createTempFile "fc-prop-" "")]
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
;; P1–P4: Totality — every function returns a valid Result, never throws
;; =============================================================================

(def gen-read-file-params
  (gen/let [name gen-filename, offset gen-offset, limit gen-limit]
    {:path (str "/tmp/fc-prop-nonexistent/" name)
     :offset offset :limit limit}))

(props/defprop-total p1-read-file-total
  fc/read-file gen-read-file-params
  {:num-tests 100 :pred result?})

(def gen-write-file-params
  (gen/let [name gen-filename, content gen-printable-content]
    {:file_path (str "/tmp/fc-prop-write/" name) :content content}))

(props/defprop-total p2-write-file-total
  fc/write-file gen-write-file-params
  {:num-tests 100 :pred result?})

(def gen-glob-params
  (gen/let [pattern gen-glob-pattern]
    {:pattern pattern :path "/tmp"}))

(props/defprop-total p3-glob-total
  fc/glob-files gen-glob-params
  {:num-tests 50 :pred result?})

(def gen-grep-params
  (gen/let [pattern gen-grep-pattern]
    {:pattern pattern :path "/tmp" :max_results 10}))

(props/defprop-total p4-grep-total
  fc/grep-files gen-grep-params
  {:num-tests 50 :pred result?})

;; =============================================================================
;; P5 — Roundtrip: write then read recovers content
;; =============================================================================

(defn- strip-line-numbers [text]
  (->> (clojure.string/split-lines text)
       (map #(clojure.string/replace-first % #"^\s+\d+\u2192" ""))
       (clojure.string/join "\n")))

(defspec p5-write-read-roundtrip 100
  (prop/for-all [content gen-printable-content
                 name gen-filename]
                (let [path (str "/tmp/fc-prop-rt/" name)
                      wr   (fc/write-file {:file_path path :content content})]
                  (if (r/err? wr)
                    true ;; vacuously true
                    (let [rd (fc/read-file {:path path})]
                      (and (r/ok? rd)
                           (= content (strip-line-numbers (:ok rd)))))))))

;; =============================================================================
;; P6 — Idempotent: reading the same file twice yields same result
;; =============================================================================

(defspec p6-read-idempotent 50
  (prop/for-all [content gen-printable-content
                 name gen-filename]
                (let [path (str "/tmp/fc-prop-idem/" name)]
                  (fc/write-file {:file_path path :content content})
                  (= (fc/read-file {:path path})
                     (fc/read-file {:path path})))))

;; =============================================================================
;; P7 — Complement: ok write => ok read (no orphan errors)
;; =============================================================================

(defspec p7-write-success-implies-readable 100
  (prop/for-all [content gen-printable-content
                 name gen-filename]
                (let [path (str "/tmp/fc-prop-comp/" name)
                      wr   (fc/write-file {:file_path path :content content})]
                  (if (r/err? wr)
                    true
                    (r/ok? (fc/read-file {:path path}))))))

;; =============================================================================
;; P8 — Read nonexistent file always returns err
;; =============================================================================

(defspec p8-read-nonexistent-is-err 100
  (prop/for-all [name gen-filename]
                (let [path (str "/tmp/fc-prop-noexist-" (System/nanoTime) "/" name)]
                  (r/err? (fc/read-file {:path path})))))

;; =============================================================================
;; P9 — Write idempotent: writing same content twice => same file contents
;; =============================================================================

(defspec p9-write-idempotent 50
  (prop/for-all [content gen-printable-content
                 name gen-filename]
                (let [path (str "/tmp/fc-prop-widem/" name)]
                  (fc/write-file {:file_path path :content content})
                  (fc/write-file {:file_path path :content content})
                  (let [rd (fc/read-file {:path path})]
                    (and (r/ok? rd)
                         (= content (strip-line-numbers (:ok rd))))))))

;; =============================================================================
;; P10 — Offset/limit: read with offset skips lines correctly
;; =============================================================================

(defspec p10-read-offset-limit 50
  (prop/for-all [lines (gen/vector gen-core/gen-non-blank-string 3 20)
                 offset (gen/choose 0 5)
                 limit (gen/choose 1 10)]
                (let [content (clojure.string/join "\n" lines)
                      path    (str "/tmp/fc-prop-offset/" (System/nanoTime) ".txt")]
                  (fc/write-file {:file_path path :content content})
                  (let [rd             (fc/read-file {:path path :offset offset :limit limit})
                        expected-count (min limit (max 0 (- (count lines) offset)))
                        result-lines   (if (clojure.string/blank? (:ok rd))
                                         []
                                         (clojure.string/split-lines (:ok rd)))]
                    (and (r/ok? rd)
                         (= expected-count (count result-lines)))))))

;; =============================================================================
;; P11 — Complement: ok? and err? are mutually exclusive on all file ops
;; =============================================================================

(defspec p11-ok-err-complement 100
  (prop/for-all [name gen-filename
                 content gen-printable-content]
                (let [path   (str "/tmp/fc-prop-xor/" name)
                      w-res  (fc/write-file {:file_path path :content content})
                      r-res  (fc/read-file {:path path})
                      nr-res (fc/read-file {:path (str "/tmp/fc-prop-xor-absent/" name)})]
                  (every? (fn [res] (not= (r/ok? res) (r/err? res)))
                          [w-res r-res nr-res]))))
