(ns basic-tools-mcp.file.search
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [basic-tools-mcp.file.path :as fp]
            [hive-system.protocols :as system]
            [hive-system.shell.core :as sh]
            [hive-weave.safe :as safe]
            [hive-weave.gate :as gate]
            [basic-tools-mcp.file.runtime :as runtime]))

(declare rg-glob glob-files grep-files)

(def ^:private max-files
  "Files a glob returns at most. One definition: the argv bound, the fallback
   bound and the notice all read it."
  1000)

(def ^:private search-timeout-ms 1000)

(def ^:private search-gate
  (gate/gate {:permits 4 :timeout-ms search-timeout-ms :name "basic-tools/file-search"}))

(defn- gated-call
  [opts f]
  (r/bind (gate/gate-run search-gate
                         #(safe/safe-future-call opts f))
          identity))

(defn- gated
  "Run F under the search gate, flattening its Result.

   Admission only — no second deadline. F is expected to own its own, which is
   what distinguishes this from `gated-call`."
  [f]
  (r/bind (gate/gate-run search-gate f) identity))

(defn- truncation-notice
  "LISTING, with a line saying so when it is not the whole answer."
  [listing truncated?]
  (if truncated?
    (str listing "\n… truncated at " max-files
         " files — narrow the pattern or the path to see the rest")
    listing))

(defn- capped
  "{:matches (at most max-files) :truncated? bool} from FOUND.

   FOUND must have been read with a budget of `(inc max-files)`: reading one
   over is what makes the cap detectable instead of merely applied."
  [found]
  {:matches    (vec (take max-files found))
   :truncated? (> (count found) max-files)})

(defn rg-glob
  "Fast bounded glob using ripgrep.

   Returns {:listing sorted-string :truncated? bool}, or nil when ripgrep
   matched nothing or could not run.

   `:truncated?` is carried out rather than swallowed. A caller handed exactly
   `max-files` names cannot otherwise tell a directory that holds that many
   from one that holds far more, and a capped list rendered as a complete one
   is a wrong answer rather than a partial one."
  [root pattern]
  (let [simple-ext? (re-matches #"^\*\.[^/]+$" pattern)
        args        (cond-> ["rg" "--files"]
                      simple-ext? (conj "--max-depth" "1")
                      :always     (conj "--glob" pattern root))
        result      (gated #(sh/lines! args {:max-lines  max-files
                                             :timeout-ms search-timeout-ms}))]
    (when (r/ok? result)
      (let [{:keys [lines truncated?]} (:ok result)]
        (when (seq lines)
          {:listing    (str/join "\n" (sort lines))
           :truncated? (boolean truncated?)})))))

(defn glob-files
  "Find files using capped ripgrep, then a gated hive-weave fallback."
  ([params]
   (glob-files (runtime/default-runtime) params))
  ([runtime {:keys [pattern path _caller_cwd]}]
   (let [root (fp/resolve-path path (or _caller_cwd (:cwd runtime)))]
     (if-let [{:keys [listing truncated?]} (rg-glob root pattern)]
       (r/ok (truncation-notice listing truncated?))
       (let [result (gated-call
                    {:timeout-ms search-timeout-ms :name "glob/fallback"}
                    ;; One MORE than the budget, so the cap is detectable rather
                    ;; than merely applied — the same defect `lines!` fixed on
                    ;; the ripgrep path, which this branch silently kept.
                    #(->> (fs/glob root pattern {:max-depth 20})
                          (take (inc max-files))
                          (mapv str)))]
         (if (r/err? result)
           (r/err :io/read-failure
                  {:message "glob_files timed out or failed — try a narrower path"
                   :path root
                   :cause (:error result)})
           (let [{:keys [matches truncated?]} (capped (:ok result))]
             (r/ok (if (seq matches)
                     (truncation-notice (str/join "\n" (sort matches)) truncated?)
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