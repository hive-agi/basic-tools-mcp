# basic-tools-mcp

<!-- hive-badges -->

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hive-agi/basic-tools-mcp.svg)](https://clojars.org/io.github.hive-agi/basic-tools-mcp)
[![cljdoc](https://cljdoc.org/badge/io.github.hive-agi/basic-tools-mcp)](https://cljdoc.org/d/io.github.hive-agi/basic-tools-mcp/CURRENT)
[![release](https://github.com/hive-agi/basic-tools-mcp/actions/workflows/release.yml/badge.svg)](https://github.com/hive-agi/basic-tools-mcp/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

<!-- /hive-badges -->

Standalone [MCP](https://modelcontextprotocol.io/) server for Clojure development tools. Wraps [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) (by Bruce Hauman) as an IAddon. Runs on [Babashka](https://babashka.org/) via the [modex-bb](https://github.com/hive-agi/modex-bb) framework.

The addon exposes **9 tools**: one `clojure` supertool (check, repair, format, eval, discover, wrap), five file tools, `todo_write`, `web_fetch`, and `web_search`. The standalone Babashka server exposes **13 tools**: five individual Clojure commands, five file tools, `todo_write`, `web_fetch`, and `web_search`. Web search requires a configured provider; the default searcher returns a configuration error.

The standalone server reads `resources/basic-tools-mcp/VERSION` from its classpath, so it works from any working directory. This resource mirrors the root `VERSION` used by releases; the parity test fails if they diverge. `config.edn` and `bb.edn` use the same modex-bb tag and commit, also checked by the parity test.

## Requirements

- [Babashka](https://github.com/babashka/babashka) v1.3.0+

## Quick Start

```bash
bb --config bb.edn run server
```

### Claude Code MCP config

Add to `~/.claude/settings.json`:

```json
{
  "mcpServers": {
    "basic-tools": {
      "command": "bb",
      "args": ["--config", "/path/to/basic-tools-mcp/bb.edn", "run", "server"]
    }
  }
}
```

## Tools

Standalone Clojure commands are individual MCP tools; in the addon they are commands of `clojure` (which also supports `wrap`).

| Standalone tool | Addon command | Description |
|------|------|-------------|
| `check` | `clojure check` | Check Clojure code for delimiter errors (mismatched parens/brackets/braces) |
| `repair` | `clojure repair` | Repair delimiter errors using parinfer (edamame + parinferish) |
| `format_code` | `clojure format` | Format Clojure code with cljfmt |
| `eval_code` | `clojure eval` | Evaluate Clojure code via nREPL (requires running nREPL server) |
| `discover` | `clojure discover` | Discover nREPL servers running on this machine |
| (addon only) | `clojure wrap` | Wrap a Clojure form at a line in a template (addon only) |

`check`, `repair`, and `format_code` accept `code` (inline string) or `file_path` (reads from disk; repair/format can write back). `eval_code` accepts `code`, `port`, `host`, and `timeout`; `discover` takes no arguments.

The following tools are exposed separately on **both** surfaces:

| Tool | Description |
|------|-------------|
| `read_file` | Read a file with optional line offset and limit |
| `file_write` | Write a file, creating parent directories as needed |
| `edit` | Replace an exact substring in a file |
| `glob_files` | Find files matching a glob |
| `grep` | Search files via ripgrep |
| `todo_write` | Replace the agent's session-scoped todo list |
| `web_fetch` | Fetch a URL through the configured fetcher (admission-gated in the default runtime) |
| `web_search` | Search via the configured searcher (default has no search provider) |

## IAddon Integration

basic-tools-mcp implements the `IAddon` protocol for dynamic registration in [hive-mcp](https://github.com/hive-agi/hive-mcp). When loaded as an addon, it registers the `clojure` supertool and eight standalone file/todo/web tools.

```clojure
(require '[basic-tools-mcp.init :as init])
(init/init-as-addon!)
;; => {:registered ["clojure" "read_file" "file_write" "edit" "glob_files" "grep"
;;                  "todo_write" "web_fetch" "web_search"] :total 9}
```

Direct handler usage:

```clojure
(require '[basic-tools-mcp.tools :as tools])
(tools/handle-clojure {:command "check" :code "(defn foo [x] (+ x 1)"})
;; => {:content [{:type "text" :text "{:has-error true, :source \"inline\"}"}]}
```

## Upstream

This project wraps [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) v0.2.1, which provides:
- **Delimiter repair** — edamame parser + parinfer (parinfer-rust when available, parinferish fallback)
- **nREPL client** — evaluation with timeout handling, persistent sessions, port discovery
- **cljfmt** — code formatting

## Local Development

For local iteration against a clojure-mcp-light checkout, create `local.config.edn` (gitignored):

```clojure
{:deps {io.github.bhauman/clojure-mcp-light {:local/root "../clojure-mcp-light"}}}
```

## Project Structure

```
src/basic_tools_mcp/
  core.clj    — Bridge to clojure-mcp-light (delimiter repair, nREPL eval, formatting)
  tools.clj   — Command handlers + MCP tool schema (IAddon interface)
  init.clj    — IAddon reify + nil-railway registration pipeline
  server.clj  — modex-bb standalone MCP server (13 tools)
  release.clj — reads VERSION for standalone server metadata
  log.clj     — Logging shim (timbre on JVM, stderr on bb)
```

## Dependencies

- [modex-bb](https://github.com/hive-agi/modex-bb) — MCP server framework
- [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) — Upstream Clojure dev tools

## License

MIT
