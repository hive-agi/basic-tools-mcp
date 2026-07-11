(ns basic-tools-mcp.architecture-test
  (:require [basic-tools-mcp.clojure.service :as service]
            [basic-tools-mcp.file-core :as file-core]
            [basic-tools-mcp.file.ports :as ports]
            [basic-tools-mcp.file.runtime :as runtime]
            [basic-tools-mcp.file.search :as search]
            [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-system.protocols :as system]
            [clojure.string :as str]))

(defn- path-query
  [exists?]
  (reify system/IPathQuery
    (path-exists? [_ _] (r/ok exists?))
    (path-directory? [_ _] (r/ok false))
    (path-file? [_ _] (r/ok exists?))
    (path-absolute? [_ _] (r/ok true))
    (path-resolve [_ base segments]
      (r/ok (str base "/" (clojure.string/join "/" segments))))
    (path-children [_ _ _] (r/ok []))))

(defn- text-files
  [state]
  (reify
    ports/ITextReader
    (read-text [_ path _opts]
      (if-let [text (get @state path)]
        (r/ok text)
        (r/err :io/not-found {:path path})))

    ports/ITextWriter
    (write-text! [_ path content _opts]
      (swap! state assoc path content)
      (r/ok {:path path}))))

(defn- stub-shell
  [calls result]
  (reify system/IShell
    (shell-exec! [_ command opts]
      (swap! calls conj {:command command :opts opts})
      result)
    (shell-env [_] {})
    (shell-which [_ program] (r/ok {:path program}))))

(deftest file-application-depends-on-ports
  (let [path "/virtual/example.clj"
        state (atom {path "(+ 1 2)\n"})
        rt    {:path-query (path-query true)
               :text-files (text-files state)
               :cwd "/virtual"}]
    (testing "read promotes text into numbered output"
      (is (= "     1→(+ 1 2)"
             (:ok (file-core/read-file rt {:path path})))))

    (testing "write crosses only injected writer"
      (is (r/ok? (file-core/write-file rt {:file_path path
                                            :content "(inc 1)\n"})))
      (is (= "(inc 1)\n" (get @state path))))

    (testing "Clojure service resolves files through same port"
      (is (= {:text "(inc 1)\n" :source path}
             (:ok (service/resolve-source rt {:file_path path})))))))

(deftest bounded-text-adapter-maps-weave-timeout
  (let [adapter (runtime/make-text-files
                 {:read-fn (fn [_]
                             (Thread/sleep 200)
                             "late")
                  :read-timeout-ms 10})
        result  (ports/read-text adapter "/slow" {})]
    (is (r/err? result))
    (is (= :io/read-failure (:error result)))
    (is (= :weave/timeout (:cause result)))))

(deftest grep-uses-injected-hive-system-shell
  (let [calls  (atom [])
        shell  (stub-shell calls
                           (r/ok {:exit 0
                                  :stdout "a.clj:1:alpha\nb.clj:2:beta\n"
                                  :stderr ""}))
        result (search/grep-files
                {:shell shell}
                {:pattern "a|b"
                 :path "/virtual"
                 :include "*.clj"
                 :max_results 1})]
    (is (= "a.clj:1:alpha" (:ok result)))
    (is (= ["rg" "--line-number" "--no-heading"
            "--glob" "*.clj" "a|b" "/virtual"]
           (:command (first @calls))))
    (is (= {:timeout-ms 10000} (:opts (first @calls))))))

(deftest grep-preserves-ripgrep-no-match-contract
  (let [result (search/grep-files
                {:shell (stub-shell (atom [])
                                    (r/ok {:exit 1 :stdout "" :stderr ""}))}
                {:pattern "missing" :path "/virtual"})]
    (is (= "No matches found" (:ok result)))))

(deftest default-runtime-composes-foundation-capabilities
  (let [rt (runtime/make-runtime {:cwd "/workspace"})]
    (is (satisfies? system/IPathQuery (:path-query rt)))
    (is (satisfies? ports/ITextReader (:text-files rt)))
    (is (satisfies? ports/ITextWriter (:text-files rt)))
    (is (satisfies? system/IShell (:shell rt)))
    (is (= "/workspace" (:cwd rt)))))
