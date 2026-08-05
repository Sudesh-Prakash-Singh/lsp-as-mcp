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
- `docker_status`
- `docker_setup`
- `docker_exec`
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

- Image: `lsp-as-mcp/sandbox:<language>-<project-hash>-<runtime>-<dockerfile-hash>-<setup-hash>`
- Build file: project-local `.lsp-as-mcp/Dockerfile` if present, otherwise `Dockerfile.lsp-tools`
- Setup: profile-defined `dockerInstallCommands`
- Project mount inside the container: `/workspace/project`
- Container reuse: one sandbox container per project root and language
- Missing image behavior: auto-build

The default Dockerfile creates a generic Ubuntu sandbox with helper commands such as `lsp-install-node` and `lsp-install-java`. The language/profile setup commands install SDKs and language servers inside the running container. The setup marker is keyed by the command list, so changed setup commands run again in a fresh sandbox state.

Manual sandbox image build:

```bash
docker build -f Dockerfile.lsp-tools -t lsp-as-mcp/sandbox:base-local .
```

Version-specific Java setup:

```bash
LSP_DOCKER_JAVA_VERSION=25 java -jar target/lsp-as-mcp.jar
```

Per-language image override:

```bash
LSP_PROFILES='{
  "java": {
    "dockerImage": "lsp-as-mcp/sandbox:base-local"
  }
}' java -jar target/lsp-as-mcp.jar
```

Per-project setup override:

```bash
LSP_PROFILES='{
  "java": {
    "dockerInstallCommands": [
      "lsp-install-java \"${LSP_DOCKER_JAVA_VERSION:-21}\"",
      "apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*",
      "command -v jdtls >/dev/null 2>&1 || (mkdir -p /opt/jdtls && curl -fsSL https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz | tar -xz -C /opt/jdtls && ln -sf /opt/jdtls/bin/jdtls /usr/local/bin/jdtls)"
    ]
  }
}' java -jar target/lsp-as-mcp.jar
```

The `docker_setup` MCP tool runs only the configured setup commands for a language/root. The `docker_exec` MCP tool runs a shell command inside the managed sandbox container, with `/workspace/project` as the working directory. It does not execute through the host shell, and it can be disabled with `LSP_DOCKER_EXEC_ENABLED=false`.

For Java, the sandbox JDK runs JDT LS and any project import/build tooling that the language server invokes. The default Java sandbox uses JDK 21. Use `LSP_DOCKER_JAVA_VERSION=25`, a version-specific image, or a project-local `.lsp-as-mcp/Dockerfile` for projects pinned to a different JDK.

Generated tags include version-sensitive build inputs, a Dockerfile fingerprint, and a setup-command fingerprint, so switching Java 21 to Java 25 or editing setup commands produces a separate sandbox.

Environment variables:

- `LSP_DOCKER_ENABLED=true|false`
- `LSP_DOCKER_IMAGE=<exact-image-override>`
- `LSP_DOCKER_AUTOBUILD=true|false`
- `LSP_DOCKER_KEEP_CONTAINERS=true|false` defaults to `true`
- `LSP_DOCKER_EXEC_ENABLED=true|false` defaults to `true`
- `LSP_DOCKER_JAVA_VERSION=21`
- `LSP_DOCKER_KOTLIN_JAVA_VERSION=25`
- `LSP_DOCKER_KOTLIN_LSP_VERSION=262.9593.0`
- `LSP_DOCKER_LUA_LS_VERSION=3.18.2`
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
- `dockerInstallCommands`
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
