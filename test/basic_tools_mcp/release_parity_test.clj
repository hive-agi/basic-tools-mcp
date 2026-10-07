(ns basic-tools-mcp.release-parity-test
  "Release metadata and documented surface parity without starting a server."
  (:require [basic-tools-mcp.init :as init]
            [basic-tools-mcp.release :as release]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]))

(defn version-sample
  "Feed version text through the reader port without filesystem effects."
  [text]
  (try
    (release/version (constantly text))
    (catch clojure.lang.ExceptionInfo _ :blank-version)))

(deftrifecta version-from-reader-contract
  basic-tools-mcp.release-parity-test/version-sample
  {:golden-path "test/golden/release/version.edn"
   :cases {:plain "0.3.14" :newline " 0.3.14\n" :blank " \n"}
   :gen gen/string-ascii
   :property-type :totality
   :mutations [["fixed-version" (fn [_] "0.2.0")]
               ["no-trim" (fn [text] text)]]})

(deftest release-and-surface-parity
  (let [readme (slurp "README.md")
        version (slurp "VERSION")
        server (slurp "src/basic_tools_mcp/server.clj")
        bb (binding [*read-eval* false] (read-string (slurp "bb.edn")))
        config (edn/read-string (slurp "config.edn"))
        addon-tools (init/register-tools!)
        addon-names (set (map :name addon-tools))
        standalone-groups {"clojure" ["check" "repair" "format_code" "eval_code" "discover"]
                           "file" ["read_file" "file_write" "edit" "glob_files" "grep"]
                           "todo" ["todo_write"]
                           "web" ["web_fetch" "web_search"]}]
    (is (= (str/trim version) (release/version (constantly version))))
    (is (= (str/trim version) (str/trim (slurp "resources/basic-tools-mcp/VERSION"))))
    (is (some #{"resources"} (:paths bb)))
    (is (str/includes? server "release/version"))
    (is (not (str/includes? server "0.2.0")))
    (is (= (get-in bb [:deps 'io.github.hive-agi/modex-bb])
           (get-in config [:deps 'io.github.hive-agi/modex-bb])))
    (is (= #{"clojure" "read_file" "file_write" "edit" "glob_files" "grep"
             "todo_write" "web_fetch" "web_search"} addon-names))
    (is (= 9 (count addon-names)))
    (doseq [[group names] standalone-groups
            :let [source (slurp (str "src/basic_tools_mcp/server/" group ".clj"))]]
      (is (str/includes? server (str "srv-" group "/" group "-tools")))
      (doseq [name names]
        (is (str/includes? source (str "(" name " ")))
        (is (str/includes? readme (str "`" name "`")))))
    (is (= 13 (count (mapcat val standalone-groups))))
    (is (str/includes? readme "9 tools"))
    (is (str/includes? readme "13 tools"))))
