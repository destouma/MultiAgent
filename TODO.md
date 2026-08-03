# TODO / Future ideas

Not scheduled, not committed to anything — just a running list of features that
would be worth building next. Grouped by area; roughly ordered by how useful
they'd be vs. how much work they are, not by priority.

## Desktop

### Proposed

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
- **Per-conversation server selection** — desktop can now pin a conversation
  to a specific saved server profile (`Conversation.serverId`, independent
  per-conversation `LlmClient`); the extension still only has the single
  app-wide active connection since it has no multi-conversation store to
  attach a `serverId` to. Would piggyback on the multi-conversation-history
  item above.
- **Publish somewhere durable** (Open VSX or an internal registry) once it's
  worth distributing beyond `npm run package` + manual `.vsix` install.
- **Message edit/regenerate, conversation search/export, and file-write
  diff/undo** — all desktop-only today (`ConversationStore`-backed, see
  ARCHITECTURE.md); porting them needs the multi-conversation-history item
  above first, since the extension's single `workspaceState` conversation has
  no per-message ids or checkpoint table to hang them off of.

## Shared / infra

- **`run_command` tool** above is a natural fit for
  `shared/workspace/workspaceService.ts`, so both clients would pick it up
  with comparatively little duplicated work.
