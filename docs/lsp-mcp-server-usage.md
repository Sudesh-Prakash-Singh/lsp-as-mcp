# LSP MCP Server Usage

This project runs a local MCP stdio server and starts language servers from profiles.

## Start

```bash
mvn package
java -jar target/llm-tools-lsp-mcp-0.1.0.jar
```

Configure your MCP client to run that command.

## Tools

- `lsp_definition`
- `lsp_profiles`
- `lsp_connect`
- `lsp_disconnect`
- `lsp_references`
- `lsp_hover`
- `lsp_document_symbols`
- `lsp_workspace_symbols`
- `lsp_open_document`
- `lsp_diagnostics`
- `lsp_request`
- `lsp_notify`
- `lsp_raw_message`

Tool arguments use normal LSP positions: zero-based `line` and `character`.

## Language Servers

The default profiles expect these commands on `PATH`:

- `typescript-language-server --stdio`
- `pyright-langserver --stdio`
- `gopls`
- `rust-analyzer`
- `jdtls`
- `clangd`

Only install the servers you need. A server is started when a tool call first needs that language/root session.

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
}' java -jar target/llm-tools-lsp-mcp-0.1.0.jar
```

Supported profile fields:

- `languageId`
- `transport`
- `command`
- `host`
- `port`
- `initializationOptions`
- `features`

You can also register a profile and connect immediately at runtime with `lsp_connect`.

Example arguments:

```json
{
  "language": "go-local",
  "root": "/path/to/project",
  "profileJson": "{\"languageId\":\"go\",\"transport\":\"stdio\",\"command\":[\"gopls\"]}"
}
```

Use `lsp_disconnect` to close the session for a language/profile and root.
