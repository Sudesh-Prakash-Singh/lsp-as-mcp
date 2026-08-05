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
- Dockerfile: `docker-langserver --stdio`

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

## Docker Fallback Mode

When a built-in local language-server command is not available and Docker is enabled, `lsp-as-mcp` falls back to Docker automatically:

1. Compute a project/language image tag such as `lsp-as-mcp/sandbox:java-<project-hash>-jdk21-<dockerfile-hash>-<setup-hash>`.
2. If the image is missing, build it from [Dockerfile.lsp-tools](Dockerfile.lsp-tools) or a project-local `.lsp-as-mcp/Dockerfile`.
3. Create or reuse one sandbox container for the requested project root and language.
4. Mount the project at `/workspace/project`.
5. Run the profile's configured `dockerInstallCommands` inside the container if setup has not already completed.
6. Start the language server inside the container with Docker exec.

The default Dockerfile creates a generic Ubuntu sandbox with helper commands such as `lsp-install-node` and `lsp-install-java`. Language runtimes, SDKs, and LSP packages are installed inside the running project container by profile setup commands. For project-specific framework tooling, override `dockerInstallCommands` in `LSP_PROFILES` or add a project-local `.lsp-as-mcp/Dockerfile`.

Build the base sandbox image manually:

```bash
docker build -f Dockerfile.lsp-tools -t lsp-as-mcp/sandbox:base-local .
```

Run Java setup with a specific JDK runtime:

```bash
LSP_DOCKER_JAVA_VERSION=25 java -jar target/lsp-as-mcp.jar
```

Use `LSP_PROFILES` when one language needs a different toolbox image than the default:

```bash
LSP_PROFILES='{
  "java": {
    "dockerImage": "lsp-as-mcp/sandbox:base-local"
  }
}' java -jar target/lsp-as-mcp.jar
```

Use `dockerInstallCommands` when a project needs extra setup before the LSP starts:

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

The `docker_setup` MCP tool runs only the configured profile setup commands for a language/root. The `docker_exec` MCP tool runs a shell command inside the managed sandbox container, with `/workspace/project` as the working directory. It does not execute through the host shell, and it can be disabled with `LSP_DOCKER_EXEC_ENABLED=false`.

For Java projects, the image JDK is the runtime used by JDT LS and project import/build tooling. The default Java sandbox uses JDK 21. Projects that need Java 25 can set `LSP_DOCKER_JAVA_VERSION=25`, point the Java profile at a version-specific `dockerImage`, or provide a project-local `.lsp-as-mcp/Dockerfile`.

Generated image tags include selected runtime versions, a Dockerfile fingerprint, and a setup-command fingerprint. That means a Java 21 sandbox, a Java 25 sandbox, and a project setup revision get separate containers instead of reusing an incompatible one.

Docker mode settings:

- `LSP_DOCKER_ENABLED`: defaults to `true`
- `LSP_DOCKER_IMAGE`: optional exact image override; unset means project/language-specific images
- `LSP_DOCKER_AUTOBUILD`: defaults to `true`
- `LSP_DOCKER_KEEP_CONTAINERS`: defaults to `true`
- `LSP_DOCKER_EXEC_ENABLED`: defaults to `true`
- `LSP_DOCKER_JAVA_VERSION`: defaults to `21`
- `LSP_DOCKER_KOTLIN_JAVA_VERSION`: defaults to `25`
- `LSP_DOCKER_KOTLIN_LSP_VERSION`: defaults to `262.9593.0`
- `LSP_DOCKER_LUA_LS_VERSION`: defaults to `3.18.2`
- `LSP_DOCKER_NODE_VERSION`: defaults to `24.12.0`

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
- `docker_status`: report Docker fallback availability and sandbox configuration
- `docker_setup`: build/start a Docker sandbox and run configured setup commands
- `docker_exec`: run a shell command inside the managed Docker sandbox
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
- `dockerCommand`
- `dockerInstallCommands`
- `dockerImage`
- `host`
- `port`
- `initializationOptions`
- `features`

See [docs/lsp-mcp-server-usage.md](docs/lsp-mcp-server-usage.md) for more usage details.
