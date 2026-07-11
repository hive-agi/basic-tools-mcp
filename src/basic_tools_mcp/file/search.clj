(ns basic-tools-mcp.file.search
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [basic-tools-mcp.file.path :as fp]
            [hive-system.protocols :as system]
            [hive-weave.safe :as safe]
            [hive-weave.gate :as gate]
            [basic-tools-mcp.file.runtime :as runtime]))

(declare rg-glob glob-files grep-files)

(def ^:private search-gate
  (gate/gate {:permits 4 :timeout-ms 1000 :name "basic-tools/file-search"}))

(defn- gated-call
  [opts f]
  (r/bind (gate/gate-run search-gate
                         #(safe/safe-future-call opts f))
          identity))

(defn- destroy-process!
  [process]
  (when (and process (.isAlive ^Process process))
    (.destroyForcibly ^Process process)))

(defn- capped-process-lines
  "Run argv with bounded time, concurrency, and output cardinality."
  [args timeout-ms max-lines]
  (let [process-ref (atom nil)
        result      (gated-call
                     {:timeout-ms timeout-ms :name (str "process/" (first args))}
                     #(let [process (.start (ProcessBuilder. ^java.util.List args))]
                        (reset! process-ref process)
                        (try
                          (with-open [reader (java.io.BufferedReader.
                                             (java.io.InputStreamReader.
                                              (.getInputStream process)))]
                            (loop [lines (transient []) count 0]
                              (if (>= count max-lines)
                                (persistent! lines)
                                (if-let [line (.readLine reader)]
                                  (recur (conj! lines line) (inc count))
                                  (persistent! lines)))))
                          (finally
                            (destroy-process! process)))))]
    (when (r/err? result)
      (destroy-process! @process-ref))
    result))

(defn rg-glob
  "Fast bounded glob using ripgrep. Returns sorted file list string or nil.

   Output is capped at 1000 lines and the child process is forcibly destroyed
   on completion, failure, or the one-second deadline."
  [root pattern]
  (let [simple-ext? (re-matches #"^\*\.[^/]+$" pattern)
        args        (cond-> ["rg" "--files"]
                      simple-ext? (conj "--max-depth" "1")
                      :always     (conj "--glob" pattern root))
        result      (capped-process-lines args 1000 1000)]
    (when (r/ok? result)
      (let [lines (:ok result)]
        (when (seq lines)
          (str/join "\n" (sort lines)))))))

(defn glob-files
  "Find files using capped ripgrep, then a gated hive-weave fallback."
  ([params]
   (glob-files (runtime/default-runtime) params))
  ([runtime {:keys [pattern path _caller_cwd]}]
   (let [root (fp/resolve-path path (or _caller_cwd (:cwd runtime)))]
     (if-let [fast-result (rg-glob root pattern)]
       (r/ok fast-result)
       (let [result (gated-call
                    {:timeout-ms 1000 :name "glob/fallback"}
                    #(->> (fs/glob root pattern {:max-depth 20})
                          (take 1000)
                          (mapv str)))]
         (if (r/err? result)
           (r/err :io/read-failure
                  {:message "glob_files timed out or failed — try a narrower path"
                   :path root
                   :cause (:error result)})
           (let [matches (:ok result)]
             (r/ok (if (seq matches)
                     (str/join "\n" (sort matches))
                     "No matches found")))))))))

(defn grep-files
  "Search through injected hive-system IShell with a hard deadline."
  ([params]
   (grep-files (runtime/default-runtime) params))
  ([{:keys [shell]} {:keys [pattern path include max_results _caller_cwd]}]
   (let [root   (fp/resolve-path (or path ".") _caller_cwd)
         limit  (or max_results 100)
         args   (cond-> ["rg" "--line-number" "--no-heading"]
                  include (into ["--glob" include])
                  :always (into [pattern root]))
         result (system/shell-exec! shell args {:timeout-ms 10000})]
     (if (r/err? result)
       (r/err :io/read-failure
              {:message "ripgrep failed or timed out"
               :path root
               :cause (:error result)})
       (let [{:keys [exit stdout stderr]} (:ok result)
             lines (when (seq stdout)
                     (take limit (str/split-lines stdout)))]
         (cond
           (or (zero? exit) (= 1 exit))
           (r/ok (if (seq lines)
                   (str/join "\n" lines)
                   "No matches found"))

           :else
           (r/err :io/read-failure
                  {:message (or (not-empty stderr) "ripgrep failed")
                   :path root
                   :exit exit})))))))