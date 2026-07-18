# Agent Developer Tools Requirements

## 1. Purpose

Build a set of MCP-accessible developer tools that make an AI coding agent materially more effective at understanding, changing, testing, and debugging software projects.

The core problem is that source text search and terminal commands are useful but incomplete. A coding agent needs reliable semantic code navigation, runtime evidence, targeted verification, reproducible state, and patch-aware review signals.

## 2. Goals

- Give agents fast, language-aware answers about code structure and behavior.
- Reduce time spent manually finding definitions, references, call paths, tests, logs, and reproduction steps.
- Make debugging evidence concrete by connecting runtime behavior back to source code.
- Help agents verify changes with the smallest meaningful test and runtime checks.
- Preserve safety by keeping project state isolated, reproducible, and inspectable.

## 3. Users

- Primary user: an AI coding agent working inside a local repository.
- Secondary user: a software developer supervising or collaborating with the agent.
- Tertiary user: CI or automation that wants the same tools without an interactive agent.

## 4. Priority Capabilities

### 4.1 Semantic Code Index

The system must provide a language-aware index over one or more repositories.

Required features:

- Find symbol definition, declaration, implementation, references, imports, exports, and overrides.
- Return call hierarchy for a function or method where supported by the language server.
- Return type information, hover documentation, diagnostics, and document symbols.
- Support workspace symbol search across the repository.
- Normalize file paths and URI formats across local and remote language servers.
- Cache recently opened files and symbol lookups to reduce repeated LSP calls.
- Expose results through MCP tools with stable JSON schemas.

Initial language targets:

- TypeScript and JavaScript
- Python
- Go
- Rust
- Java
- C and C++

Acceptance criteria:

- Given a file, line, and character, the agent can resolve the symbol definition.
- Given a symbol location, the agent can list references with file, line, character, and snippet context.
- Given a repository root, the agent can search workspace symbols without opening every file manually.

### 4.2 Runtime Observability Harness

The system must run or attach to a local application and collect evidence from real execution.

Required features:

- Start configured services with project-native commands such as npm, cargo, pytest, or custom scripts.
- Capture stdout, stderr, structured logs, browser console output, network requests, HTTP responses, screenshots, and process exit codes.
- Drive API requests and browser flows from scripted scenarios.
- Correlate errors, stack traces, and source maps back to repository files when possible.
- Provide a single run report containing commands, timestamps, artifacts, failures, and source references.

Acceptance criteria:

- Given a configured command and health check, the harness starts the app and reports readiness or failure.
- Given a browser flow, the harness records screenshots, console errors, failed requests, and final URL.
- Given a stack trace, the harness links frames to local files when paths are resolvable.

### 4.3 Test Impact Analysis

The system must recommend a targeted verification plan for a local code change.

Required features:

- Inspect the current git diff.
- Map changed files and symbols to likely affected tests.
- Prefer project-native test commands discovered from package files, build files, CI config, or existing scripts.
- Distinguish fast checks, focused tests, integration tests, and full-suite validation.
- Explain why each test command was selected.

Acceptance criteria:

- Given a diff touching one module, the tool returns the most relevant focused tests and type checks.
- Given a shared utility or public API change, the tool broadens the recommended checks.
- The tool never claims a change is fully verified unless the relevant commands actually ran successfully.

### 4.4 Safe State Sandbox

The system must provide disposable, repeatable application state for integration debugging.

Required features:

- Create and reset local service state for databases, queues, caches, object storage, and auth fixtures.
- Support seed profiles such as empty, minimal, realistic, and bug reproduction.
- Snapshot and restore state between test runs.
- Keep generated state out of source-controlled project files unless explicitly requested.
- Report all ports, credentials, temporary directories, and fixture paths used for a sandbox.

Acceptance criteria:

- A sandbox can be created, used, reset, and destroyed without changing committed files.
- The same seed profile produces the same logical records across runs.
- Failed setup includes actionable logs and the command that failed.

### 4.5 Patch-Aware Review Assistant

The system must review local changes against repository context before final response or commit.

Required features:

- Analyze git diff, changed files, nearby code, tests, and public interfaces.
- Detect likely behavioral regressions, missing edge cases, inconsistent local patterns, and missing tests.
- Prioritize findings by severity and include file and line references.
- Separate concrete bugs from style suggestions and open questions.
- Avoid rewriting unrelated code.

Acceptance criteria:

- Given a local diff, the tool returns findings grounded in changed lines or affected contracts.
- Findings include reproduction or verification guidance when available.
- If no issues are found, the tool states residual risk and test coverage checked.

### 4.6 Reproduction Builder

The system must convert a failure signal into a durable local reproduction.

Required inputs:

- Error logs
- Stack traces
- Failed HTTP requests
- Browser console errors
- User-provided bug reports
- Failing test output

Required features:

- Identify the likely entry point and failing code path.
- Generate a minimal test, script, fixture, or request file inside the appropriate project test structure.
- Preserve original failure evidence in a short comment or test name where useful.
- Run the reproduction and report whether it fails before any fix.

Acceptance criteria:

- Given a stack trace and repository root, the tool proposes or creates a failing test near the relevant module.
- Given an HTTP failure, the tool can create a repeatable request script or integration test.
- The tool clearly distinguishes a confirmed reproduction from an unverified hypothesis.

## 5. MVP Scope

The first release should focus on the highest-leverage path:

1. Extend the existing LSP MCP server into a stronger semantic code index.
2. Add git-diff-aware test recommendation.
3. Add a lightweight command runner that captures logs, exit codes, and artifacts.
4. Add a patch review command that combines diff, semantic lookups, and test recommendations.

MVP tools:

- `code_definition`
- `code_references`
- `code_workspace_symbols`
- `code_call_hierarchy`
- `code_diagnostics`
- `change_test_plan`
- `run_verification`
- `review_patch`

## 6. Non-Goals

- Replace full IDEs.
- Replace CI.
- Provide perfect static analysis for every language.
- Automatically fix every review finding.
- Run unsafe destructive commands without explicit approval.
- Store secrets, credentials, or private code outside the local environment.

## 7. System Requirements

- Run locally on Linux or WSL.
- Expose all capabilities through MCP tools.
- Support workspace roots outside the tool repository.
- Handle local stdio language servers and already-running TCP language servers consistently.
- Return machine-readable JSON plus concise human-readable summaries.
- Fail with actionable errors rather than silent empty results.
- Avoid network access unless explicitly configured by the user.

## 8. Security And Safety

- Treat the target repository as untrusted input.
- Do not execute project scripts without user or caller approval policy.
- Redact common secret formats from logs and reports.
- Keep sandbox data isolated from the source tree by default.
- Never delete files, branches, databases, or other persistent state without an explicit destructive-action request.
- Record every executed command, working directory, environment override, and exit code.

## 9. Configuration

The system should support a project-local configuration file.

Example:

```json
{
  "workspaceRoot": "/path/to/project",
  "profiles": {
    "typescript": {
      "transport": "stdio",
      "command": ["typescript-language-server", "--stdio"]
    }
  },
  "verification": {
    "fast": ["npm run typecheck", "npm test -- --runInBand"],
    "full": ["npm test"]
  },
  "runtime": {
    "start": "npm run dev",
    "health": "http://127.0.0.1:3000/health"
  },
  "sandbox": {}
}
```

Configuration precedence:

1. Explicit MCP tool arguments
2. Project-local config
3. Environment variables
4. Safe defaults

## 10. Observability Artifacts

Each runtime or verification run should create an artifact bundle containing:

- `summary.json`
- `commands.json`
- `stdout.log`
- `stderr.log`
- `diagnostics.json`
- `screenshots/` when browser checks run
- `network.json` when HTTP or browser network capture is enabled
- `review.md` when patch review runs

Artifacts should be stored outside tracked source files by default, such as under `/tmp/llm-tools-runs`.

## 11. Success Metrics

- Time to find a relevant symbol or reference is under 2 seconds for warm cache on medium repositories.
- Test recommendation includes the correct focused test command for common project layouts.
- Runtime run reports contain enough evidence to diagnose failed startup or failed browser flow without rerunning manually.
- Patch review findings are mostly actionable and include file references.
- Reproduction builder can create a confirmed failing test or request for common stack traces and HTTP failures.

## 12. Open Questions

- Should the semantic index persist to disk or remain session-local initially?
- Should runtime browser automation depend on Playwright, or should it be an optional integration?
- What approval model should govern execution of project commands through MCP?
- What sandboxing model is useful without making local LSP startup more complicated?
- Should review output be optimized for interactive chat, PR comments, or both?
