---
name: lsp-as-mcp
description: "Use the lsp-as-mcp server for semantic code navigation, change impact analysis, types, and diagnostics. Select this skill when coding work benefits from its connected LSP tools, or when configuring or troubleshooting this server. Gate every LSP feature call on project readiness and reuse existing sessions."
---

# LSP as MCP

Use text search to locate files and literal text; use LSP tools when symbol identity,
types, or diagnostic evidence matter. Read the [workflow reference](references/workflows.md)
for document synchronization, diagnostics, or advanced requests. Read the
[setup reference](references/setup.md) only for configuration or startup problems.

## Select the session

- Discover the connected server's actual tool names and schemas. Names below are
  logical tool names, not a fixed namespace such as `mcp__lsp__`.
- Reuse the language/profile and project root of a relevant eager-started session.
  Use a one-time unfiltered `status` if that context is unknown. Otherwise inspect
  build manifests to choose the relevant project, including nested projects.
- Use `profiles` only when profile selection is uncertain. A profile key can be
  an alias such as `python-custom`; it need not equal the document language ID.
  Declared `features` are hints, not negotiated server capabilities.
- If MCP tools are unavailable, continue with source inspection and explain the
  limitation. Do not install executables or change client configuration merely
  because a semantic query would be useful.

## Mandatory readiness gate

Before **each** `definition`, `references`, `hover`, `symbols`, `workspace_symbols`,
`open`, `diagnostics`, `request`, `notify`, or `raw` call:

1. Start a 60-second readiness budget. Call `status` with the exact `language`
   and `root` intended for that operation. Inspect the matching entry in
   `sessions`; only `state: "ready"` permits the feature call.
2. If no session exists on the initial check, call `connect_session_start` once
   for that pair, then check filtered `status` again. This bootstrap does not
   replace an existing session. Its hook output is not evidence of readiness.
   If no matching session exists after bootstrap, stop that attempt.
3. For `created`, `initializing`, `loading`, `closing`, or an unrecognized state,
   wait and recheck with delays of 1, 2, 4, then 8 seconds; keep the 8-second
   interval. Clip each delay to the remaining budget. Never busy-poll. Use the
   available wait facility and continue independent source inspection if useful.
4. Stop immediately on `failed`, bootstrap/status errors, or a session disappearing
   after it was observed. Report the error or unavailable state; do not keep
   recreating sessions. At 60 seconds, stop waiting and fall back to source/text
   search. Do not call the blocked feature anyway or automatically restart the
   same wait cycle for the same unresolved operation.
5. Once filtered status confirms `ready` within the budget, issue only the intended
   feature call. Before the next feature, perform a new gate. Do not cache a ready
   observation, batch several feature calls behind one check, or skip the check
   after `open`. Other activity or the feature itself can start background work.

`status`, `profiles`, `connect_session_start`, `connect`, and `disconnect` are
session-management tools exempt from the gate. Prefer session reuse: `connect`
disconnects and replaces the session even with `profileJson: "{}"`. Use it only
for intentional profile changes or recovery; do not reconnect or disconnect as
routine query preparation or cleanup.

The gate confirms the **latest observed** state, not an atomic guarantee during
the subsequent request. `ready` means initialized with no reported active work;
it does not prove indexing is complete. `progressObserved: false` means no
work-done progress has been observed, not that loading has finished. Percentages
belong to individual tasks. Missing/unknown status support is not readiness.

## Choose a focused query

| Need | Tool and scope |
| --- | --- |
| Find a named symbol in the project | `workspace_symbols` with a specific name |
| Inspect a file's structure | `symbols` for that file |
| Follow the symbol at a position | `definition` |
| Inspect callers/usages before a change | `references`; exclude declarations unless needed |
| Get types or documentation | `hover` |
| Inspect published errors/warnings | `diagnostics` filtered by file |

Prefer typed tools. Use `request` for a specific unsupported typed operation,
`notify` for a known notification, and `raw` only when full JSON-RPC control is
necessary. They still require the readiness gate. Do not guess that a method is
supported from a profile's feature list.

Avoid empty workspace queries, repeated identical queries, and project-wide
diagnostics unless the task requires that scope. Read bounded source excerpts
around returned locations, deduplicate repeated locations, and narrow a query
when results are too broad. Refresh source positions after edits.

## Keep source and evidence accurate

- Pass absolute file paths or file URIs. Relative file paths resolve against the
  MCP process's working directory, **not** the supplied project root.
- Lines and characters are zero-based. Character offsets use UTF-16 code units:
  a supplementary Unicode character occupies two units. Derive positions from
  the current file content, placing the position inside the intended identifier.
- Track files opened through this skill by session and URI, including their
  last submitted content/version. `open` always sends `didOpen`; it is not an
  update operation. Follow the workflow reference before refreshing an edited
  open document, and do not blindly close a document with unknown lifecycle.
- Diagnostics are cached asynchronous publications, not an on-demand validation
  result. No record and an explicit empty diagnostic record have different
  meanings. Receipt timestamps do not prove correspondence to the latest edit.
- Report what the tools establish and any unresolved limits. Empty references
  or diagnostics do not independently establish safety. Verify changes with
  appropriate project tests as well as semantic evidence.
