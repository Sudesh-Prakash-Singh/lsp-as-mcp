# LSP MCP Server Usage

This project runs a local MCP stdio server and starts language servers from profiles.

## Start

```bash
mvn package
java -jar target/lsp-as-mcp.jar
```

Configure your MCP client to run that command.

For Codex, use a short server key in `~/.codex/config.toml` so the tool names stay compact:

```toml
[mcp_servers.lsp]
enabled = true
command = "java"
args = ["-jar", "D:\\fun-projects\\lsp-as-mcp\\target\\lsp-as-mcp.jar"]
```

Set `cwd` only if you use a relative jar path such as `target/lsp-as-mcp.jar`; the project root to analyze is supplied in each MCP tool call.

## Tools

- `definition`
- `profiles`
- `connect`
- `status`: inspect existing project sessions and reported work-done progress
- `disconnect`
- `references`
- `hover`
- `symbols`
- `workspace_symbols`
- `open`
- `diagnostics`
- `request`
- `notify`
- `raw`

Tool arguments use normal LSP positions: zero-based `line` and `character`.

## Language Servers

The default profiles first try these local commands on `PATH`:

- `typescript-language-server --stdio`
- `pyright-langserver --stdio`
- `gopls`
- `rust-analyzer`
- `jdtls`
- `clangd`
- `intelephense --stdio`
- `solargraph stdio`
- `csharp-ls`
- `kotlin-lsp --stdio`
- `lua-language-server`
- `yaml-language-server --stdio`
- `vscode-json-language-server --stdio`
- `bash-language-server start`
Only install the servers you need. A server is started when a tool call first needs that language/root session. If the command is missing, the tool call returns the command it tried and the profile's install hint.

## Custom Profiles

Use `LSP_PROFILES` to override built-ins or add new profiles.

```bash
LSP_PROFILES='{
  "typescript": {
    "languageId": "typescript",
    "transport": "stdio",
    "command": ["typescript-language-server", "--stdio"]
  },
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

You can also register a profile and connect immediately at runtime with `connect`.

Example arguments:

```json
{
  "language": "go-local",
  "root": "/path/to/project",
  "profileJson": "{\"languageId\":\"go\",\"transport\":\"stdio\",\"command\":[\"gopls\"]}"
}
```

Use `disconnect` to close the session for a language/profile and root.

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
