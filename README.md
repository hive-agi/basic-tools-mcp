# basic-tools-mcp

Standalone [MCP](https://modelcontextprotocol.io/) server for Clojure development tools. Wraps [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) (by Bruce Hauman) as an IAddon. Runs on [Babashka](https://babashka.org/) via the [modex-bb](https://github.com/hive-agi/modex-bb) framework.

Provides 5 tools: delimiter error detection/repair, code formatting, nREPL evaluation, and nREPL server discovery.

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

| Tool | Description |
|------|-------------|
| `check` | Check Clojure code for delimiter errors (mismatched parens/brackets/braces) |
| `repair` | Repair delimiter errors using parinfer (edamame + parinferish) |
| `format_code` | Format Clojure code with cljfmt |
| `eval_code` | Evaluate Clojure code via nREPL (requires running nREPL server) |
| `discover` | Discover nREPL servers running on this machine |

All tools accept either `code` (inline string) or `file_path` (reads from disk, writes back for repair/format).

## IAddon Integration

basic-tools-mcp implements the `IAddon` protocol for dynamic registration in [hive-mcp](https://github.com/hive-agi/hive-mcp). When loaded as an addon, it registers the `clojure` supertool.

```clojure
(require '[basic-tools-mcp.init :as init])
(init/init-as-addon!)
;; => {:registered ["clojure"] :total 1}
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
  server.clj  — modex-bb standalone MCP server (5 tools)
  log.clj     — Logging shim (timbre on JVM, stderr on bb)
```

## Dependencies

- [modex-bb](https://github.com/hive-agi/modex-bb) — MCP server framework
- [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) — Upstream Clojure dev tools

## License

MIT
