(ns basic-tools-mcp.file.runtime
  "CPPB Boundary composition for file and search capabilities.

   Raw JVM file effects live in BoundedTextFiles. Path and shell capabilities
   come from hive-system; potentially blocking text effects are bounded by
   hive-weave. Application namespaces receive the resulting runtime map and
   therefore depend on ports rather than concrete host APIs."
  (:require [babashka.fs :as fs]
            [basic-tools-mcp.file.ports :as ports]
            [hive-dsl.result :as r]
            [hive-system.fs.core :as hfs]
            [hive-system.shell.core :as shell]
            [hive-weave.safe :as weave]))

(def ^:private default-read-timeout-ms 10000)
(def ^:private default-write-timeout-ms 10000)

(defn- ensure-parent!
  [path]
  (when-let [parent (fs/parent path)]
    (when-not (fs/exists? parent)
      (fs/create-dirs parent))))

(defn- boundary-error
  [category operation path result]
  (r/err category
         {:message (str operation " failed: " path)
          :path    (str path)
          :cause   (:error result)
          :details (dissoc result :error)}))

(defrecord BoundedTextFiles
  [read-fn write-fn ensure-parent-fn read-timeout-ms write-timeout-ms]
  ports/ITextReader
  (read-text [_ path opts]
    (let [timeout-ms (or (:timeout-ms opts) read-timeout-ms)
          result     (weave/safe-future-call
                      {:timeout-ms timeout-ms
                       :name       (str "text/read " path)}
                      #(read-fn path))]
      (if (r/ok? result)
        result
        (boundary-error :io/read-failure "Read" path result))))

  ports/ITextWriter
  (write-text! [_ path content opts]
    (let [timeout-ms (or (:timeout-ms opts) write-timeout-ms)
          result     (weave/safe-future-call
                      {:timeout-ms timeout-ms
                       :name       (str "text/write " path)}
                      #(do
                         (when (:create-parents? opts true)
                           (ensure-parent-fn path))
                         (write-fn path content)
                         {:path (str path)}))]
      (if (r/ok? result)
        result
        (boundary-error :io/write-failure "Write" path result)))))

(defn make-text-files
  "Build bounded JVM text adapter. Effect fns are injectable for contract tests."
  ([] (make-text-files {}))
  ([{:keys [read-fn write-fn ensure-parent-fn
            read-timeout-ms write-timeout-ms]
     :or   {read-fn            slurp
            write-fn           (fn [path content] (spit path content))
            ensure-parent-fn   ensure-parent!
            read-timeout-ms    default-read-timeout-ms
            write-timeout-ms   default-write-timeout-ms}}]
   (->BoundedTextFiles read-fn write-fn ensure-parent-fn
                       read-timeout-ms write-timeout-ms)))

(defn make-runtime
  "Compose host capabilities. Callers may replace any port independently."
  ([] (make-runtime {}))
  ([{:keys [path-query text-files shell cwd]
     :or   {path-query (hfs/make-path-query)
            text-files (make-text-files)
            shell      (shell/make-shell)
            cwd        (System/getProperty "user.dir")}}]
   {:path-query path-query
    :text-files text-files
    :shell      shell
    :cwd        cwd}))

(defonce ^:private default-runtime-ref
  (delay (make-runtime)))

(defn default-runtime
  "Process-default runtime used by backward-compatible one-arity APIs."
  []
  @default-runtime-ref)
