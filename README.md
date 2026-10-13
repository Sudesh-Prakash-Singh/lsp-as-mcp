# lsp-as-mcp

`lsp-as-mcp` is a Spring Boot MCP stdio server that acts as a small language-server client. It lets MCP clients call local LSP features such as definitions, references, hover information, document symbols, workspace symbols, and diagnostics.

## Requirements

- Java 21
- Maven
- One or more language servers on `PATH`

Default local language-server commands:

- TypeScript/JavaScript: `typescript-language-server --stdio`
- Python: `pyright-langserver --stdio`
- Go: `gopls`
- Rust: `rust-analyzer`
- Java: `jdtls`
- C/C++: `clangd`
- PHP: `intelephense --stdio`
- Ruby: `solargraph stdio`
- C#: `csharp-ls`
- Kotlin: `kotlin-lsp --stdio`
- Lua: `lua-language-server`
- YAML: `yaml-language-server --stdio`
- JSON: `vscode-json-language-server --stdio`
- Bash: `bash-language-server start`
Only the language servers you use need to be installed.

If a configured language-server command is missing, the MCP tool call fails with the command it tried and an install hint for that profile.

## Build

```bash
mvn package
```

The packaged jar is written to:

```text
target/lsp-as-mcp.jar
```

## Run

```bash
java -jar target/lsp-as-mcp.jar
```

The server runs over stdio and is intended to be launched by an MCP client.

## Codex Configuration

Example `~/.codex/config.toml` entry:

```toml
[mcp_servers.lsp]
enabled = true
command = "java"
args = ["-jar", "D:\\fun-projects\\lsp-as-mcp\\target\\lsp-as-mcp.jar"]
```

Using a short MCP server key such as `lsp` keeps exposed tool names compact.
Set `cwd` only if you use a relative jar path such as `target/lsp-as-mcp.jar`; the project root to analyze is supplied in each MCP tool call.

## Tools

- `profiles`: list configured LSP profiles and declared features
- `connect`: register or update a profile, then initialize a session
- `status`: inspect existing project sessions and reported work-done progress
- `disconnect`: close a language/root session
- `definition`: find definitions for a source position
- `references`: find references for a source position
- `hover`: get hover/type information
- `symbols`: list document symbols
- `workspace_symbols`: search workspace symbols
- `open`: open or refresh a document before diagnostics
- `diagnostics`: return the latest published diagnostics
- `request`: send a raw LSP request
- `notify`: send a raw LSP notification
- `raw`: send a full JSON-RPC message

Tool positions use normal LSP coordinates: zero-based `line` and `character`.

## Custom Profiles

Use `LSP_PROFILES` to override built-in profiles or add a new profile:

```bash
LSP_PROFILES='{
  "java-tcp": {
    "languageId": "java",
    "transport": "tcp",
    "host": "127.0.0.1",
    "port": 2093
  }
}' java -jar target/lsp-as-mcp.jar
```

Supported profile fields:

- `languageId`
- `transport`
- `command`
- `host`
- `port`
- `initializationOptions`
- `features`
- `installHint`

See [docs/lsp-mcp-server-usage.md](docs/lsp-mcp-server-usage.md) for more usage details.

## Project status and progress

Call `status` with no arguments to list existing sessions, or pass optional
`language` and `root` filters (a filesystem path or file URI). It never starts a
language server. No matching session returns `{"sessions": []}`; disconnected
sessions are removed from the list. Poll the tool to see progress updates.

```json
{"language": "java", "root": "/path/to/project"}
```

Each session contains `language`, `root` (file URI), `state`, `initialized`,
`progressObserved`, `progress` (active tasks), `lastProgress` (latest update,
including completed tasks), and `error` (last connection/initialization failure).
States are `created`, `initializing`, `loading`, `ready`, `closing`, or `failed`.
Failed sessions remain visible until disconnected or replaced by `connect`.
Failures before a session can be created, such as a missing executable, are
returned by the calling tool and do not create a status entry.

Progress uses standard LSP work-done notifications (`$/progress`) and supports
server-initiated `window/workDoneProgress/create` requests. Tasks preserve the
server's token, title, message, percentage and cancellable flag when supplied,
plus an `updatedAt` timestamp. Percentages belong to individual tasks; there is
no invented overall project percentage. Partial-result notifications are ignored.

`ready` means initialization completed and no reported work-done task is active.
It does **not** guarantee indexing has finished: language servers may perform
background work without reporting it. `progressObserved: false` means no work-done
progress has been received yet. This tool provides snapshots, not MCP push updates.
