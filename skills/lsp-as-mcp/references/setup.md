# Startup configuration and troubleshooting

Read this reference when the user wants setup or an unavailable session needs
configuration diagnosis. The coding skill does not require eager startup or a
Codex hook. Do not change unrelated configuration or install servers as a side
effect of a query.

## Connect the MCP server

The application requires Java 21, the packaged server JAR, and installed language
server executables for the profiles in use. Configure a local stdio MCP server
using `java -jar /absolute/path/to/lsp-as-mcp.jar`. The server key may be `lsp` or
another name; discover the actual exposed tool names after connecting.

Example Codex configuration (replace paths):

```toml
[mcp_servers.lsp]
command = "java"
args = ["-jar", "/absolute/path/to/lsp-as-mcp.jar"]

[mcp_servers.lsp.env]
LSP_STARTUP_CONFIG = "/absolute/path/to/lsp-startup.json"
```

Use `cwd` if the JAR path is relative. Project roots are supplied through startup
configuration or tools. The stdio language-server process runs in its project's
root; tool file paths should still be absolute or file URIs. TCP profiles connect
to existing servers rather than starting an executable.

## Configure eager sessions

Single session: set both `LSP_STARTUP_LANGUAGE` (profile key) and
`LSP_STARTUP_ROOT` (path or file URI). Existing profile settings are used.

```bash
LSP_STARTUP_LANGUAGE=python LSP_STARTUP_ROOT=/workspace/app \
  java -jar /absolute/path/to/lsp-as-mcp.jar
```

Optionally set `LSP_STARTUP_COMMAND` to a single executable path or a JSON argument
array. It overrides that profile's command and selects stdio. A plain string is
one executable path, including spaces; it is not parsed as a shell command.

```bash
LSP_STARTUP_LANGUAGE=typescript LSP_STARTUP_ROOT=/workspace/app \
  LSP_STARTUP_COMMAND='["/opt/bin/typescript-language-server","--stdio"]' \
  java -jar /absolute/path/to/lsp-as-mcp.jar
```

Multiple sessions: set `LSP_STARTUP_SESSIONS` to a JSON array. Do not combine it
with any single-session `LANGUAGE`, `ROOT`, or `COMMAND` variable.

```json
[
  {"language": "python", "root": "/workspace/app"},
  {"language": "go", "root": "/workspace/service"}
]
```

Alternatively, `LSP_STARTUP_CONFIG` accepts a path or file URI to a JSON object
with optional `profiles` and `sessions`:

```json
{
  "profiles": {
    "python-custom": {
      "languageId": "python",
      "command": ["/opt/bin/pyright-langserver", "--stdio"]
    },
    "go-tcp": {
      "languageId": "go",
      "transport": "tcp",
      "host": "127.0.0.1",
      "port": 2093
    }
  },
  "sessions": [
    {"language": "python-custom", "root": "./app"},
    {"language": "go-tcp", "root": "./service"}
  ]
}
```

Profile fields are `languageId`, `transport` (`stdio` or `tcp`), `command` (an
argument array), `host`, `port`, `initializationOptions`, `features`, and
`installHint`. Profiles merge with built-ins; custom keys default their language
ID to the key, so set `languageId` explicitly for aliases.

Precedence and paths:

- Built-in profile fields, then file profile fields, then `LSP_PROFILES` fields.
  `LSP_PROFILES` is a JSON object keyed by profile name. A single-session
  `LSP_STARTUP_COMMAND` overrides its command last.
- Environment session configuration replaces file sessions, rather than appending.
  `LSP_STARTUP_SESSIONS='[]'` explicitly selects no eager sessions.
- Relative file project roots resolve against the configuration file's directory.
  Environment roots resolve against the MCP process's working directory. Use
  absolute executable paths for custom commands.
- Duplicate normalized language/root pairs initialize once. Startup sessions
  initialize sequentially, awaiting the initialization handshake. Invalid
  configuration or initialization failure stops MCP startup.
- No startup configuration means lazy initialization. Use `connect_session_start`
  once to create a missing session without replacing it, then confirm `ready` via
  filtered `status`. Its returned hook text is not a readiness signal.

## Server-specific workspace arguments

No temporary workspace or language-specific arguments are automatically added.
For JDT LS, specify its internal workspace explicitly when needed:

```json
{"java": {"command": ["jdtls", "-data", "/workspace/jdt-data"]}}
```

This object can be used as `LSP_PROFILES` or as the file's `profiles` value.
The JDT workspace is distinct from the source project root. Give separate
simultaneously running JDT processes separate internal workspaces to avoid locks.
Without `-data`, location selection depends on the installed launcher; do not
invent or promise a default directory.

## Diagnose configuration problems

If the MCP server never becomes available, inspect its startup error and configured
environment/file. Startup may fail before `status` can be called. Check required
paired variables, JSON shapes, file access, executable paths, and TCP endpoint.
Do not attempt LSP features while the readiness gate is unsatisfied.

For a reachable MCP server, inspect `profiles` when the profile is unknown and
filtered `status` for the selected root. Missing commands return the attempted
command and an install hint; do not keep issuing the same failing bootstrap.
Fix the configuration within the user's task or report the precise blocker.

When a deliberate runtime profile change is needed, use `connect` with a
JSON-string `profileJson`. It disconnects the old language/root session first
and updates the named profile. Initialization success is not project readiness:
check `status` again before features. Other roots using the same profile retain
their existing clients until reconnected. `disconnect` removes the selected
session from status. Do not use either tool as routine query preparation.
