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
- `docker-langserver --stdio`

Only install the servers you need. A server is started when a tool call first needs that language/root session.

## Docker Fallback

If a built-in local command is missing and Docker is available, the server automatically falls back to a project/language sandbox image.

Default behavior:

- Image: `lsp-as-mcp/sandbox:<language>-<project-hash>-<runtime>-<dockerfile-hash>`
- Build file: project-local `.lsp-as-mcp/Dockerfile` if present, otherwise `Dockerfile.lsp-tools`
- Build arg: `LSP_LANGUAGE=<language>`
- Project mount inside the container: `/workspace/project`
- Container reuse: one sandbox container per project root and language
- Missing image behavior: auto-build

Manual Java image build:

```bash
docker build --build-arg LSP_LANGUAGE=java -f Dockerfile.lsp-tools -t lsp-as-mcp/sandbox:java-local .
```

Version-specific Java image:

```bash
docker build --build-arg LSP_LANGUAGE=java --build-arg JAVA_VERSION=21 -f Dockerfile.lsp-tools -t lsp-as-mcp/sandbox:java21 .
```

Per-language image override:

```bash
LSP_PROFILES='{
  "java": {
    "dockerImage": "lsp-as-mcp/sandbox:java21"
  }
}' java -jar target/lsp-as-mcp.jar
```

For Java, the sandbox JDK runs JDT LS and any project import/build tooling that the language server invokes. The default Java sandbox uses JDK 21. Use `LSP_DOCKER_JAVA_VERSION=25`, a version-specific image, or a project-local `.lsp-as-mcp/Dockerfile` for projects pinned to a different JDK.

Generated tags include version-sensitive build inputs and a Dockerfile fingerprint, so switching Java 21 to Java 25 or editing `.lsp-as-mcp/Dockerfile` produces a separate sandbox image.

Environment variables:

- `LSP_DOCKER_ENABLED=true|false`
- `LSP_DOCKER_IMAGE=<exact-image-override>`
- `LSP_DOCKER_AUTOBUILD=true|false`
- `LSP_DOCKER_KEEP_CONTAINERS=true|false`
- `LSP_DOCKER_JAVA_VERSION=21`
- `LSP_DOCKER_KOTLIN_JAVA_VERSION=25`
- `LSP_DOCKER_NODE_VERSION=24.12.0`

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
- `dockerCommand`
- `dockerImage`
- `host`
- `port`
- `initializationOptions`
- `features`

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
