(ns basic-tools-mcp.root-tool-contract-test
  (:require [hive-addon.tool-contract.test :refer [deftest-root-tools]]
            [hive-addon.protocol :as proto]
            [basic-tools-mcp.init :as a]))

(deftest-root-tools root-tools (proto/tools (a/addon-ctor {})))
