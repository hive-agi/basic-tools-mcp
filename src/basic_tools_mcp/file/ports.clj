(ns basic-tools-mcp.file.ports
  "Application-owned text-file ports.

   hive-system supplies host capability protocols such as IPathQuery and
   IShell.  Basic-tools owns the narrower text contract its domain needs.
   Keeping read and write capabilities separate follows ISP and makes command
   services easy to test without touching the host filesystem."
  (:refer-clojure :exclude [read]))

(defprotocol ITextReader
  "Bounded text reads. Implementations return hive-dsl Result values."
  (read-text [this path opts]
    "Read path as text. opts may contain :timeout-ms."))

(defprotocol ITextWriter
  "Bounded text writes. Implementations return hive-dsl Result values."
  (write-text! [this path content opts]
    "Write text to path. opts may contain :timeout-ms and :create-parents?."))
