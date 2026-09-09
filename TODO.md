# TODO / Future ideas

Not scheduled, not committed to anything — just a running list of features that
would be worth building next. Grouped by area; roughly ordered by how useful
they'd be vs. how much work they are, not by priority.

The `desktop-java/` client is the reference implementation; its own backlog
lives in [`desktop-java/ARCHITECTURE.md` §11](./desktop-java/ARCHITECTURE.md#11-ideas-not-yet-implemented).

## VS Code extension

The extension is the remaining TypeScript client (`../shared/`). Most of these
are "port what `desktop-java/` already has":

- **Port orchestrator mode** (plan → specialists → synthesize).
- **Port image generation** — needs an `ImageService` equivalent; the
  extension's workspace tools intentionally exclude `generate_image` for now.
- **Multi-conversation history** — the extension persists a single conversation
  per workspace (`workspaceState`); `desktop-java/` has full
  create/switch/delete. Prerequisite for most of the items below.
- **Per-conversation server selection** — `desktop-java/` pins a conversation to
  a saved server profile (`Conversation.serverId`, independent per-conversation
  `LlmClient`); the extension still has one app-wide connection. Piggybacks on
  multi-conversation history.
- **Message edit/regenerate, conversation search/export, file-write diff/undo**
  — all in `desktop-java/` (`ConversationStore`-backed); porting needs the
  multi-conversation-history item first (no per-message ids or checkpoint table
  in the single `workspaceState` conversation).
- **`run_command` / build-test loop** — shipped in `desktop-java/`
  (`RunCommandService`, approval-gated); the extension could pick up an
  equivalent in `shared/workspace/`.
- **Publish somewhere durable** (Open VSX or an internal registry) once it's
  worth distributing beyond `npm run package` + manual `.vsix` install.
