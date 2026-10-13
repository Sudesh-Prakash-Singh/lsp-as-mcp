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

Set `cwd` only if you use a relative jar path such as `target/lsp-as-mcp.jar`; project roots are supplied through startup configuration or MCP tool calls.

## Tools

- `definition`
- `profiles`
- `connect`
- `connect_session_start`
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
Only install the servers you need. Configured startup sessions initialize during application startup. Other sessions start when a tool call first needs that language/root pair. If the command is missing, the tool call returns the command it tried and the profile's install hint.

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

## Eager startup

Eager startup is optional and works with any profile/language, for either stdio
or TCP. Sessions initialize during MCP application startup, before tool calls are
needed. Later tools reuse the session for the same language and normalized root.
The `connect` tool explicitly replaces a session; use feature tools or
`connect_session_start` to reuse one.

For a single session, set both `LSP_STARTUP_LANGUAGE` and `LSP_STARTUP_ROOT`:

```bash
LSP_STARTUP_LANGUAGE=python LSP_STARTUP_ROOT=/path/to/project \
  java -jar target/lsp-as-mcp.jar
```

The profile's existing command, transport, initialization options, and features
are used. `LSP_PROFILES` still supports overrides and custom profiles.
Optionally set `LSP_STARTUP_COMMAND` to an executable path or a JSON command
array; this selects stdio and overrides that profile's command:

```bash
LSP_STARTUP_LANGUAGE=typescript LSP_STARTUP_ROOT=/path/to/project \
  LSP_STARTUP_COMMAND='["/opt/bin/typescript-language-server","--stdio"]' \
  java -jar target/lsp-as-mcp.jar
```

A plain command value is one executable path (spaces are preserved). It is not
parsed as shell text. Use a JSON array for arguments. No language-specific
arguments or temporary workspaces are automatically added. For JDT LS, provide
`-data` and a suitable workspace path explicitly if needed.

For multiple sessions, set `LSP_STARTUP_SESSIONS` to a JSON array:

```bash
LSP_STARTUP_SESSIONS='[
  {"language":"python","root":"/path/to/project"},
  {"language":"go","root":"/path/to/project"},
  {"language":"rust","root":"/path/to/another-project"}
]' java -jar target/lsp-as-mcp.jar
```

Each entry requires `language` (a profile key) and `root` (path or file URI).
Duplicate language/root pairs are initialized only once. Environment roots
resolve relative to the MCP process's working directory. Do not combine the
session-list variable with the single-session variables.

### JSON configuration file

Set `LSP_STARTUP_CONFIG` to the path or file URI of a JSON file:

```bash
LSP_STARTUP_CONFIG=/path/to/lsp-startup.json java -jar target/lsp-as-mcp.jar
```

```json
{
  "profiles": {
    "python-custom": {
      "languageId": "python",
      "command": ["/opt/bin/pyright-langserver", "--stdio"],
      "initializationOptions": {}
    },
    "go-tcp": {
      "languageId": "go",
      "transport": "tcp",
      "host": "127.0.0.1",
      "port": 2093
    }
  },
  "sessions": [
    {"language": "python-custom", "root": "./project"},
    {"language": "go-tcp", "root": "./other-project"}
  ]
}
```

Both `profiles` and `sessions` are optional. File profiles use the same fields
as `LSP_PROFILES` and merge with built-in profiles. Relative file roots resolve
against the configuration file's directory; absolute paths and file URIs are
preserved. Prefer absolute executable paths for custom commands. TCP profiles
connect to an already-running server.

Precedence:

1. Built-in profiles, then file profile fields, then `LSP_PROFILES` fields.
2. `LSP_STARTUP_COMMAND`, when used, overrides the single session's command.
3. Environment session configuration replaces the file's session list.
   `LSP_STARTUP_SESSIONS='[]'` explicitly selects no startup sessions.

Invalid configuration, unknown profiles, missing executables, or initialization
failures stop MCP startup with an error. Session errors identify the language
and project root. Sessions initialize sequentially and await the LSP initialization
handshake; language-server indexing may continue afterward. Use `status` to inspect
reported loading progress. Without startup configuration, sessions remain lazy.

Example MCP client configuration using a file:

```toml
[mcp_servers.lsp]
command = "java"
args = ["-jar", "/absolute/path/to/lsp-as-mcp.jar"]

[mcp_servers.lsp.env]
LSP_STARTUP_CONFIG = "/absolute/path/to/lsp-startup.json"
```

Or configure a single session directly in the MCP client's environment:

```toml
[mcp_servers.lsp.env]
LSP_STARTUP_LANGUAGE = "python"
LSP_STARTUP_ROOT = "/path/to/project"
```

No Codex hook is required for eager startup. The optional `connect_session_start`
tool remains available for clients that want Codex SessionStart hook output.
