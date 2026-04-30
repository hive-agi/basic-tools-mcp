(ns basic-tools-mcp.log
  "Logging shim — delegates to timbre on JVM, stderr on babashka."
  (:require [hive-dsl.result :as r]))

(def ^:private use-timbre?
  (and (nil? (System/getProperty "babashaka.version"))
       (some? (r/rescue nil (requiring-resolve 'taoensso.timbre/info)))))

(defn stderr-log [level args]
  (binding [*out* *err*]
    (println (str "[" level "]") (apply str (interpose " " args)))))

(defmacro info  [& args] (if use-timbre? `(taoensso.timbre/info  ~@args) `(stderr-log "INFO"  (list ~@args))))
(defmacro warn  [& args] (if use-timbre? `(taoensso.timbre/warn  ~@args) `(stderr-log "WARN"  (list ~@args))))
(defmacro error [& args] (if use-timbre? `(taoensso.timbre/error ~@args) `(stderr-log "ERROR" (list ~@args))))
(defmacro debug [& args] (if use-timbre? `(taoensso.timbre/debug ~@args) `(stderr-log "DEBUG" (list ~@args))))
