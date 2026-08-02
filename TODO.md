# TODO / Future ideas

Not scheduled, not committed to anything — just a running list of features that
would be worth building next. Grouped by area; roughly ordered by how useful
they'd be vs. how much work they are, not by priority.

## Desktop

### Proposed

- **Per-conversation server selection.** Right now the LLM connection
  (`providerType`/`baseUrl`/`apiKey`/`maxHistory`) is app-global — switching
  servers in Settings affects every conversation. The **model** is already
  per-conversation (`conversations.model` column, per-chat `ModelPicker`); the
  natural next step is making the **server** per-conversation too, so a chat
  bound to Lemonade keeps talking to Lemonade even after you switch the
  active server elsewhere. Would need: a `serverId` column on `conversations`
  (mirroring `model`), and `ChatService`/`OrchestratorService`/`ImageService`
  resolving a client per-request (or a small `Map<serverId, LlmClient>` cache)
  instead of the current single swapped-out `getClient()`/`setClient()`
  singleton. Pairs nicely with split view — compare the same prompt against
  two different servers side by side, not just two personas.

- **AI dev loop.** Extend the workspace tool loop (`list_dir`/`read_file`/
  `write_file`/`delete_file` in `shared/workspace/workspaceService.ts`) with a
  `run_command` tool that executes a shell command in the bound folder,
  captures stdout/stderr/exit code, and feeds it back to the model — so it can
  run `npm test` / `npm run build`, read the failure, fix it, and re-run,
  looping until it passes or hits a cap (mirroring the existing
  `MAX_TOOL_ROUNDS/MAX_SPECIALIST_TOOL_ROUNDS` pattern in `chatService.ts`/
  `orchestratorService.ts`). **Safety note:** running arbitrary commands is a
  materially bigger risk than sandboxed file I/O under the workspace root —
  this should default off (like the VS Code extension's
  `enableWorkspaceTools`), probably need per-command confirmation or a scoped
  allowlist (`npm test`, `npm run build`, `npm run lint`, ...) rather than
  free-form shell access, and a hard wall-clock timeout per command.

### Suggested

- **Message edit + regenerate.** No way today to edit a sent message or
  regenerate a reply without retyping — one of the more commonly-missed
  features in a first-pass chat UI.
- **True concurrent generation across split-view panes.** Documented as
  out-of-scope in `ARCHITECTURE.md`: `ChatService`/`OrchestratorService` each
  track a single in-flight `AbortController`, so the UI has to block Send in
  the idle pane while the other streams. Swapping that single controller for
  a `Map<conversationId, AbortController>` would let both panes genuinely
  stream at once.
- **Diff view + an undo/checkpoint for AI file writes.** `write_file`/
  `delete_file` touch disk immediately with no review step. A lightweight
  before/after diff when the model edits an existing file, plus a way to
  revert the last AI-made change, would matter a lot more once the dev-loop
  feature above lets the model make several unattended edits in a row.
- **Conversation/message search.** Folders + conversations grow fast; there's
  no way to search across titles or message content today.
- **Export a conversation** to Markdown or JSON — easy given the SQLite store,
  useful for sharing or archiving.
- **Context/token usage indicator** near the composer, so a
  `context_exceeded` error (see the existing error mapping in
  `shared/llm/*.ts`) is something you see coming rather than hit.
- **Code-sign the Windows installer.** Confirmed unsigned today — CI has no
  `CSC_LINK`/certificate wired in, so the NSIS installer trips SmartScreen and
  can't be verified as coming from a specific publisher.
- **Auto-updater** (`electron-updater`) so installed copies can update without
  a manual reinstall — relevant now that CI produces a real installer artifact
  on every master push.

## VS Code extension

- **Port orchestrator mode** (plan → specialists → synthesize) — desktop-only
  today.
- **Port image generation** — needs an `ImageService` equivalent; currently
  the extension's workspace tools intentionally exclude `generate_image` for
  this reason.
- **Multi-conversation history** — the extension persists a single
  conversation per workspace (`workspaceState`); desktop has full
  create/switch/delete.
- **Publish somewhere durable** (Open VSX or an internal registry) once it's
  worth distributing beyond `npm run package` + manual `.vsix` install.

## Shared / infra

- **Per-conversation server selection** and **`run_command` tool** above are
  both natural fits for `shared/llm/` and `shared/workspace/` respectively,
  so both clients would pick them up with comparatively little duplicated
  work.
