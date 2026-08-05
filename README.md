# lsp-as-mcp

`lsp-as-mcp` is a Spring Boot MCP stdio server that acts as a small language-server client. It lets MCP clients call local LSP features such as definitions, references, hover information, document symbols, workspace symbols, and diagnostics.

## Requirements

- Java 21
- Maven
- One or more language servers on `PATH`

Default language-server commands:

- TypeScript/JavaScript: `typescript-language-server --stdio`
- Python: `pyright-langserver --stdio`
- Go: `gopls`
- Rust: `rust-analyzer`
- Java: `jdtls`
- C/C++: `clangd`

Only the language servers you use need to be installed.

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
cwd = "D:\\fun-projects\\lsp-as-mcp"
```

Using a short MCP server key such as `lsp` keeps exposed tool names compact.

## Tools

- `profiles`: list configured LSP profiles and declared features
- `connect`: register or update a profile, then initialize a session
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

See [docs/lsp-mcp-server-usage.md](docs/lsp-mcp-server-usage.md) for more usage details.
