# TODO / Future ideas

Not scheduled, not committed to anything — a running list of what would be worth
building next, grouped by area. Roughly ordered by usefulness vs. effort, not by
priority.

`desktop-java/` is the reference client; the fuller rationale for its items
(security sketches, dependency notes) lives in
[`desktop-java/ARCHITECTURE.md` §11](./desktop-java/ARCHITECTURE.md#11-ideas-not-yet-implemented).
This file is the cross-project index.

---

## Desktop (`desktop-java/`)

**Shipped since these were first listed:** vision (`describe_image` + image
attachments), `run_command` (sandboxed build/test, Phase 1), the orchestrator
executor rework, the Settings persona editor.

### Backlog

| Feature | Effort | Need | Risk | One-liner |
| --- | --- | --- | --- | --- |
| Git commit-message-from-diff | Low | Med–High | Low | One-click "draft a commit message from what's staged" on the existing `git_diff` / `git_commit` tools |
| Pin / star a conversation | Low | Med–High | Very low | Boolean column + sidebar toggle; easy to lose a chat now that folders *and* projects both nest the list |
| Cross-folder project "notes" | Low | Med | Low | One free-text field per project, injected into every chat's system prompt across that project's folders (like the workspace tree already is) |
| Split view "send to both panes" | Low–Med | Med | Low | Fire one prompt at both panes at once — real model-vs-model / server-vs-server A/B |
| Command palette (Ctrl+K) | Med | Med | Very low | Jump to any conversation / folder / project by typing; `SearchDialog` today is content search, not a navigator |
| Replay / send HTTP from the Debug panel | Low–Med | Low–Med | Low–Med | "Resend" (optionally edited) a captured exchange + a blank request form — a small built-in REST client for poking a local server's non-chat endpoints. Human-driven, debug-only |
| Embedded workspace terminal | Med–High | Med–High | Low–Med | A real interactive shell pane (cwd = the bound folder) next to a future file-browser panel, for what `run_command` deliberately can't do (dev servers, interactive git). Human-driven so no approval gate — but adds a native PTY dependency (pty4j / JediTerm) that complicates the flat `jpackage` input. Overlaps `run_command` Phase 2 — design together |
| `run_command` Phase 2 | Med | Med | Low–Med | Long-running dev servers with a process panel (stop / port / log-tail). Shares a "processes in this workspace" model with the terminal above |
| SAST / SBOM launcher in-app | Med | Low–Med | Low–Med | Run Syft / Grype / CodeQL-style scans as a first-class in-app action instead of the manual outside-the-app flow. Blocked on nailing down its shape first |
| **Model-invoked HTTP(S) fetch tool** | Med | **High** | **High** | Let the model itself fetch a URL / hit an API. Highest-demand item — but an unrestricted fetch tool is an SSRF + exfiltration primitive; the denylist / allowlist / approval design *is* the work. See §11 for a defensible shape |
| Docker tool integration | Med–High | Low–Med | **High** | `GitService`-style allowlisted `docker` / `compose` subcommands. `build` = arbitrary host code exec; blast radius is the whole host. `run_command` blocks `docker` / `podman` / `kubectl` on purpose. Deferred until a concrete need — see §11 for the proposed read-only vs. gated vs. excluded tiers |

**Sequencing:** the top three are quick, wanted, near-riskless — do those first,
then split-send and the palette. The HTTP fetch tool is the highest-value item
but its security design must be deliberate, not rushed. The terminal + `run_command`
Phase 2 come once a file-browser panel exists and should be designed as one thing.
Defer Docker; SAST waits on deciding its shape.

### Config, not code

- **Pick a default VLM** for vision — the mechanism ships; what's left is choosing
  which model to point the Vision dropdown at (Qwen2.5-VL / Qwen3-VL for
  OCR-heavy work, Gemma 3 as an all-rounder, Moondream2 / SmolVLM for tiny/fast).
  llama.cpp needs `--mmproj`; confirm the server actually accepts the image.
  Omni models aren't worth it. Full notes in §11.

---

## VS Code extension

The extension is the remaining TypeScript client (`vscode-extension/shared/`).
Most of these are "port what `desktop-java/` already has":

- **Port orchestrator mode** (plan → specialists → synthesize).
- **Port image generation** — needs an `ImageService` equivalent; the extension's
  workspace tools intentionally exclude `generate_image` for now.
- **Multi-conversation history** — the extension persists a single conversation
  per workspace (`workspaceState`); `desktop-java/` has full create/switch/delete.
  Prerequisite for most of the items below.
- **Per-conversation server selection** — `desktop-java/` pins a conversation to a
  saved server profile (`Conversation.serverId`, independent per-conversation
  `LlmClient`); the extension still has one app-wide connection. Piggybacks on
  multi-conversation history.
- **Message edit/regenerate, conversation search/export, file-write diff/undo** —
  all in `desktop-java/` (`ConversationStore`-backed); porting needs the
  multi-conversation-history item first.
- **`run_command` / build-test loop** — shipped in `desktop-java/`
  (`RunCommandService`, approval-gated); the extension could pick up an equivalent
  in `shared/workspace/`.
- **Publish somewhere durable** (Open VSX or an internal registry) once it's worth
  distributing beyond `npm run package` + manual `.vsix` install.

---

## Repo / infra

- **`release-verify/` has no tracked content** — it's only a `.gitignore` target
  for scan output. Commit the actual SAST/SBOM scripts + config if it should be a
  real component.
- **JetBrains plugin** — planned fourth surface. It's JVM, so it could reuse
  `desktop-java/`'s `service/` + `llm/` layer rather than reimplement.
- **Merge `dev` → `master` + tag** on each `desktop-java/` release (the CI
  installer matrix in `.github/workflows/desktop-java.yml` fires on `v*` tags).
