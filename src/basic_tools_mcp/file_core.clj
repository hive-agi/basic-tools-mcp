(ns basic-tools-mcp.file-core
  "File I/O implementations for basic-tools-mcp.

   Returns Result ADT values: (ok text) or (err :io/... {:message ...}).
   JVM-compatible: uses slurp/spit, babashka.fs, clojure.java.shell."
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [hive-dsl.result :as r]))

(defn- format-numbered-lines
  "Format lines with line numbers, applying offset and limit."
  [text offset limit]
  (let [lines    (str/split-lines text)
        selected (->> lines (drop offset) (take limit))
        numbered (map-indexed
                  (fn [i line]
                    (format "%6d\u2192%s" (+ offset i 1) line))
                  selected)]
    (str/join "\n" numbered)))

(defn read-file
  "Read a file with optional offset and limit. Returns numbered lines."
  [{:keys [path offset limit]}]
  (let [offset (or offset 0)
        limit  (or limit 2000)]
    (if (fs/exists? path)
      (r/try-effect* :io/read-failure
                     (format-numbered-lines (slurp path) offset limit))
      (r/err :io/not-found {:message (str "File not found: " path)
                            :path path}))))

(defn write-file
  "Write content to a file. Creates parent directories if needed."
  [{:keys [file_path content]}]
  (r/try-effect* :io/write-failure
                 (let [parent (fs/parent file_path)]
                   (when (and parent (not (fs/exists? parent)))
                     (fs/create-dirs parent))
                   (spit file_path content)
                   (str "File written: " file_path))))

(defn glob-files
  "Find files matching a glob pattern."
  [{:keys [pattern path]}]
  (let [root (or path (System/getProperty "user.dir"))]
    (r/try-effect* :io/read-failure
                   (let [matches (->> (fs/glob root pattern)
                                      (map str)
                                      sort
                                      (take 1000))]
                     (if (seq matches)
                       (str/join "\n" matches)
                       "No matches found")))))

(defn grep-files
  "Search for pattern using ripgrep."
  [{:keys [pattern path include max_results]}]
  (let [root  (or path ".")
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
