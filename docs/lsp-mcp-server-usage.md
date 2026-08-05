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
cwd = "D:\\fun-projects\\lsp-as-mcp"
```

## Tools

- `definition`
- `profiles`
- `connect`
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
