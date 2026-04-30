(ns basic-tools-mcp.file.path
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(declare resolve-path format-numbered-lines)

(defn resolve-path
  "Resolve path against _caller_cwd when relative. Absolute paths pass through."
  [path caller-cwd]
  (if (and path caller-cwd (not (fs/absolute? path)))
    (str (fs/path caller-cwd path))
    (or path caller-cwd)))

(defn format-numbered-lines
  "Format lines with line numbers, applying offset and limit."
  [text offset limit]
  (let [lines    (str/split-lines text)
        selected (->> lines (drop offset) (take limit))
        numbered (map-indexed
                  (fn [i line]
                    (format "%6d\u2192%s" (+ offset i 1) line))
                  selected)]
    (str/join "\n" numbered)))
