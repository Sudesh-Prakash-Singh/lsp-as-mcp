# Semantic coding workflows

Every feature invocation below requires its own filtered readiness gate from
`SKILL.md`. A recipe's arrows describe useful decisions, not permission to send
several calls after a single status check. Skip steps that add no evidence.

## Understand code

Use file discovery/text search when the file or symbol spelling is unknown.
For a known symbol name, use a focused `workspace_symbols` query. For a known
file, use `symbols` if its structure would help. Read the relevant source, derive
the identifier position, then use `definition` to follow the symbol. Use `hover`
when types or documentation resolve a remaining question.

Definitions may be a single location, a list of locations, or location links.
For a location, read `uri` and `range`; for a location link, read `targetUri` and
`targetSelectionRange` (or `targetRange` for the surrounding declaration).
Document symbols can be hierarchical; follow children relevant to the question.
Deduplicate locations before reading source. Convert zero-based line numbers to
one-based numbers for user-facing source references.

## Assess and verify a change

Locate the declaration and ask `references` for the usages needed to assess
impact. `includeDeclaration` defaults to false; enable it only if the declaration
belongs in the results. Inspect the relevant callers and edit with the normal
filesystem/editor workflow. The MCP server has no typed editing or rename tool.

Invalidate cached positions and query results for changed code. If the agent has
opened an affected document in the LSP session, synchronize it before using
semantic results to assess the edited content. Inspect file diagnostics with
the freshness limits below, then run tests appropriate to the actual change.
References can omit dynamic usages or uses in other projects; supplement with
text search when that matters.

## Document lifecycle and synchronization

Opening is needed for file diagnostics and for servers that require a document
to be open for a query. Do not submit full file content for every navigation call.
Read current content, pass the profile's language ID (or let `open` infer it),
and record the session/URI, version, and submitted content after a successful
open. Track actual invocations; do not assume a file is open merely because
another feature queried it.

The current `open` tool sends `textDocument/didOpen` every time, despite describing
itself as a refresh operation. Avoid duplicate opens without an intervening close.
For a file **known to have been opened by this agent in this session** whose
content changed:

1. Read its new complete content and derive new positions.
2. Gate readiness, then use `notify` with method `textDocument/didClose`.
3. Record the document as closed once the notification was successfully sent.
4. Gate readiness again, then call `open` with the new complete content and a
   tracked version greater than the previous submitted version. Record it as open.
5. Gate readiness anew before diagnostics or any subsequent semantic query.

Example close arguments after confirming readiness:

```json
{
  "language": "python-custom",
  "root": "/workspace/app",
  "method": "textDocument/didClose",
  "paramsJson": "{\"textDocument\":{\"uri\":\"file:///workspace/app/main.py\"}}"
}
```

If reopening is blocked, leave the document marked closed and report that the
refresh was not completed. Do not query the blocked session. If another client
or an earlier context may own the open document and its lifecycle is unknown,
do not blindly close/reopen it; inspect saved source and describe the uncertainty
until lifecycle is established. Invalidate document tracking after a known
session replacement or disappearance.

The server neither tracks open documents for the agent nor exposes negotiated
text synchronization capabilities. Do not assume generic `didChange` payloads
are valid. Use server-specific synchronization only when its capabilities and
payload are established. Closing returns document ownership to the saved file;
ensure intended edits are saved before the close/reopen workflow.

## Investigate diagnostics

For the relevant file, open its current content once if its lifecycle permits,
then perform a fresh readiness gate and request file-filtered `diagnostics`.
Read the diagnostic range and message, inspect source at that position, and
follow definitions or hover information only if they help explain the issue.

The tool returns a list of cached publication records, each containing `uri`,
`diagnostics`, and `receivedAt`:

- `[]`: no matching publication has been cached. This is not a clean result.
- A record containing `diagnostics: []`: the last publication for that file
  reported no diagnostics. It is not automatically proof about current content.
- `receivedAt`: when this MCP server received the publication. It is not the
  document version or a guarantee of when analysis ran. The server does not
  expose the publication's document version or clear old records on reopen.

If a publication is expected after opening/editing, retry only when its arrival
would help. Use at most three additional diagnostics reads, with delays of 1, 2,
and 4 seconds, each preceded by the readiness gate. Stop when relevant new
evidence arrives or a gate fails; do not create another retry cycle. A newer
receipt alone still cannot prove correspondence to the latest edit. Report
unverified freshness and use project tests or other validation where appropriate.
Do not reopen a document repeatedly merely to provoke diagnostics.

## Handle empty results or errors

Before a corrected retry, inspect the selected root/profile, the current source
position and its UTF-16 offset, known document content, and the session state.
Make a specific correction rather than repeating an identical unsuccessful query.
An empty response can be legitimate or reflect incomplete server knowledge.

On a method-not-found or unsupported-feature error, use another supported tool
or source inspection. On a timeout, inspect filtered status rather than assuming
the session failed or is ready. On explicit failure, report its error. An
intentional recovery may use `connect`, which replaces the session and invalidates
document tracking, followed by a new readiness gate. Do not auto-reconnect in a loop.

For advanced methods, `request` accepts a JSON string in `paramsJson` and handles
request IDs. `notify` also accepts JSON-string params. Build these strings with a
JSON serializer rather than hand escaping user text. `raw` accepts a complete
JSON-RPC object as a string: an ID waits for a response; no ID sends a notification.
Normal requests time out after 15 seconds; the MCP tools expose no timeout override.
None of these escape hatches bypass readiness or imply support for the method.

## Check difficult positions

Do not copy byte offsets, terminal display columns, or one-based search output
directly into a position. Count the UTF-16 units before the identifier on its
line. For a zero-based character index into a Python string, the corresponding
offset is `len(line[:index].encode("utf-16-le")) // 2`. Recompute after each edit.
For example, in `"😀target"`, `target` starts at LSP character 2, not 1 or 4.
