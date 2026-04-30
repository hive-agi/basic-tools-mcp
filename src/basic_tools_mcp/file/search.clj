(ns basic-tools-mcp.file.search
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [basic-tools-mcp.file.path :as fp]))

(declare rg-glob glob-files grep-files)

(defn rg-glob
  "Fast glob using ripgrep. Returns sorted file list string, or nil if rg unavailable/slow.
   Reads at most 1000 lines from rg output (no slurp), with 1s hard timeout.
   Respects .gitignore — skips target/, node_modules/, .git/ etc."
  [root pattern]
  (let [simple-ext? (re-matches #"^\*\.[^/]+$" pattern)
        args        (cond-> ["rg" "--files"]
                      simple-ext? (conj "--max-depth" "1")
                      :always     (conj "--glob" pattern root))
        result      (deref
                      (future
                        (r/rescue nil
                          (let [proc   (.start (ProcessBuilder. ^java.util.List args))
                                reader (java.io.BufferedReader.
                                         (java.io.InputStreamReader.
                                           (.getInputStream proc)))
                                lines  (loop [acc (transient []) i 0]
                                         (if (>= i 1000)
                                           (persistent! acc)
                                           (if-let [line (.readLine reader)]
                                             (recur (conj! acc line) (inc i))
                                             (persistent! acc))))]
                            (.destroyForcibly proc)
                            lines)))
                      1000  ;; 1s hard timeout
                      nil)]
    (when (seq result)
      (str/join "\n" (sort result)))))

(defn glob-files
  "Find files matching a glob pattern.
   Uses ripgrep for speed (<1s), falls back to fs/glob with 1s timeout."
  [{:keys [pattern path _caller_cwd]}]
  (let [root (fp/resolve-path path (or _caller_cwd (System/getProperty "user.dir")))]
    (r/try-effect* :io/read-failure
      (or
        (rg-glob root pattern)
        (let [result (deref
                       (future
                         (->> (fs/glob root pattern {:max-depth 20})
                              (take 1000)
                              (mapv str)))
                       1000
                       ::timeout)]
          (cond
            (= result ::timeout)
            "glob_files timed out — try a more specific pattern or narrower path"

            (seq result)
            (str/join "\n" (sort result))

            :else
            "No matches found"))))))

(defn grep-files
  "Search for pattern using ripgrep."
  [{:keys [pattern path include max_results _caller_cwd]}]
  (let [root  (fp/resolve-path (or path ".") _caller_cwd)
        limit (or max_results 100)
        args  (cond-> ["rg" "--line-number" "--no-heading"]
                include (into ["--glob" include])
                :always (into [pattern root]))]
    (r/try-effect* :io/read-failure
                   (let [result (apply shell/sh args)
                         lines  (when (seq (:out result))
                                  (->> (str/split-lines (:out result))
                                       (take limit)))]
                     (if (seq lines)
                       (str/join "\n" lines)
                       "No matches found")))))
