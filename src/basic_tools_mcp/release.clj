(ns basic-tools-mcp.release
  "Release metadata shared by the standalone server and parity checks."
  (:require [clojure.string :as str]))

(defn version
  "Return the trimmed release version produced by the injected `read-version`
   thunk; throws when it is blank. The standalone server passes a reader of the
   classpath resource basic-tools-mcp/VERSION, which the parity test keeps
   equal to the root VERSION used by releases."
  [read-version]
  (let [value (str/trim (read-version))]
    (when (str/blank? value)
      (throw (ex-info "VERSION must not be blank" {})))
    value))
