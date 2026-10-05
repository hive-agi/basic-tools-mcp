(ns basic-tools-mcp.file-write-guard-test
  "Pure write validation trifecta and a real handler non-truncation regression."
  (:require [basic-tools-mcp.file-core :as fc]
            [basic-tools-mcp.tools.file :as tools]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftrifecta validate-write-content
  basic-tools-mcp.file-core/validate-write-content
  {:golden-path "test/golden/file-write/validate-write-content.edn"
   :cases {:missing [{:file_path "x" :text "oops" :new_body "lost"} nil]
           :nil-content [{:content nil} nil]
           :new-file-empty [{:content ""} nil]
           :existing-empty [{:content ""} "original"]
           :explicit-empty [{:content "" :allow_empty true} "original"]
           :nonempty [{:content "replacement"} "original"]}
   :gen (gen/tuple (gen/hash-map :content gen/string-ascii)
                   (gen/one-of [(gen/return nil) gen/string-ascii]))
   :property-type :totality
   :mutations [["always-ok" (fn [_ _] {:ok nil})]
               ["always-missing" (fn [_ _] {:error :input/missing})]
               ["ignore-existing" (fn [params _] (if (some? (:content params)) {:ok nil} {:error :input/missing}))]]})

(deftest handler-refuses-missing-content-without-truncating
  (let [dir (Files/createTempDirectory "write-guard-" (make-array FileAttribute 0))
        path (.resolve dir "study-guide.txt")
        original (apply str (repeat 32768 "x"))]
    (try
      (spit (str path) original)
      (doseq [params [{:text "lost"} {:new_body "lost"} {:content nil} {:content ""}]]
        (testing (str "refused " params)
          (let [response (tools/handle-file-write (assoc params :file_path (str path)))
                message (get-in response [:content 0 :text])]
            (is (true? (:isError response)))
            (when (nil? (:content params))
              (is (str/includes? message "content"))
              (doseq [key (filter #(contains? params %) [:text :new_body])]
                (is (str/includes? message (name key)))))
            (is (= original (slurp (str path)))))))
      (is (not (:isError (tools/handle-file-write {:file_path (str path)
                                                    :content "" :allow_empty true}))))
      (is (= "" (slurp (str path))))
      (finally
        (Files/deleteIfExists path)
        (Files/deleteIfExists dir)))))
