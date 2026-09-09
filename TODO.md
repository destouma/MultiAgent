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

## JetBrains plugin

Planned fourth surface, and the cheapest port so far: it's JVM, so it can **reuse
`desktop-java/`'s non-UI code directly** instead of reimplementing it the way the
VS Code extension had to. Everything from `ChatViewModel` down —
`service/` (`ChatService`, `OrchestratorService`, `ToolLoopRunner`,
`CheckpointService`, …), `llm/`, `workspace/`, `persistence/`, `model/`,
`action/` — has no JavaFX dependency and is already callback-based. That's ~7–8k
of ~10.5k LOC reused; the work is swapping the JavaFX layer (~2–3k LOC) for
IntelliJ Platform equivalents, most of which are platform widgets you get for
free.

### How the layers map

| `desktop-java/` today | Plugin equivalent |
| --- | --- |
| `App` / `MainWindow` (Stage, sidebar `TreeView`, topbar) | `ToolWindowFactory` + tool-window panel; the IDE Project view replaces the folder sidebar |
| `ChatPaneView` / `ChatThread` / `Composer` (JavaFX) | Swing / `JBUI` components + a small markdown renderer, **or** a `JBCefBrowser` webview shared with `vscode-extension/` |
| `ChatViewModel` `Observable*` + `Platform.runLater` | plain listeners + `Application.invokeLater(…, ModalityState)` onto the EDT |
| `%APPDATA%/MultiAgentJava/config.json` via `ConfigService` | same file under `PathManager.getConfigPath()/multiagent/`, or `PersistentStateComponent` |
| `chats.db` (sqlite-jdbc) | same, under the plugin config/system dir — **native-lib extraction under the plugin classloader is the one real technical risk; spike it first** |
| `DirectoryChooser` to bind a folder | nothing — the chat binds to the open `Project` (`project.getBasePath()`) automatically |
| `DialogActionApprover` (JavaFX) | `DialogWrapper` / `Messages`; `ActionApprover` interface unchanged |
| `DiffDialog` (custom, java-diff-utils) | `DiffManager.showDiff(...)` — drop the custom one |
| `GitService` (shells out, allowlisted) | keep as-is; revisit `Git4Idea` later |
| after `write_file` | `VfsUtil.markDirtyAndRefresh(...)` so open editors update |
| `mvn javafx:run` / `jpackage` / WiX | Gradle + IntelliJ Platform Gradle Plugin 2.x; distribute via Marketplace or a plugin zip |
| 3 bundled themes incl. Terminal | follow the IDE theme via `JBColor` |

### Not worth carrying over

| Feature | Why |
| --- | --- |
| Side-by-side split view of two conversations | the IDE already splits editors; two chat panes in a narrow tool window is cramped. If model-vs-model A/B matters, do it as two tool-window tabs later |
| Bundled light / dark / terminal themes | the IDE owns theming; Terminal is a novelty |
| `jpackage` / WiX / `Launcher` indirection | Marketplace / plugin zip handles distribution |
| `MainWindow` sidebar folder `TreeView` | replaced by the IDE Project view + a conversation list in the tool window |
| Directory chooser for workspace binding | the open project *is* the binding |
| Raw API debug-log window | niche; IntelliJ ships an HTTP Client — keep at most a log file |
| In-app persona *editor* dialog | personas matter; "edit the JSON + a Settings list" is enough for a long time |

### Reuse strategy — pick one

- **A. Shared `core` module (recommended).** Extract the UI-free packages into a
  module both `desktop-java/` and `intellij-plugin/` depend on. Finally fixes the
  `shared/`-is-TS-only lesson with a real JVM core. Cost: a build restructure
  (Maven multi-module, or the plugin consumes `core` from `mavenLocal`; the plugin
  itself must be Gradle).
- **B. Fork the core into the plugin** — same move as `desktop/` → `desktop-java/`.
  Faster start, but two copies of `ToolLoopRunner` et al. Only if the plugin is a
  short experiment.

### Phases

| Phase | Scope | Exit |
| --- | --- | --- |
| **0 — Foundations** | Extract `core` (strategy A); confirm zero `javafx.*` leaks and `desktop-java/` still builds. Gradle plugin skeleton, empty tool window, `runIde`. **Spike sqlite-jdbc under the plugin classloader.** Decide Swing vs JCEF. | Sandbox IDE opens the tool window; a throwaway button round-trips a `ConversationStore` row |
| **1 — Minimal chat** | Single conversation (thread + composer). Server / model / persona config as an IDE `Configurable` reusing `AppSettings`. Health indicator. Streaming plain chat via `ChatService`. Persistence via reused `ConversationStore`. Callback→EDT helper. | Streaming conversation; history survives IDE restart |
| **2 — Workspace-assisted chat** | Auto-bind to the open `Project`. Wire `ToolLoopRunner` + `WorkspaceService` + `GitService` + `RunCommandService`. Approval gate → `DialogWrapper`. Tool-activity rows. `VfsUtil.markDirtyAndRefresh` after writes. Checkpoint diff/revert via `DiffManager`. `run_command` output surfaced. | "add a null check and run the tests" → approval → edits visible in the editor → diff/revert → test output |
| **3 — IDE-native integration** | Editor context-menu actions (**Add selection**, **Explain**); auto-include active file / selection as context. Conversation list + per-conversation model/server/persona pinning. Search + export MD/JSON. Status-bar widget (server + model + health). | Driven from the editor, not just the tool window; several conversations |
| **4 — Orchestrator** | Wire `OrchestratorService` (plan → specialists → synthesize) with progress in the tool window. Coordinator picker + per-specialist models dialog. Specialists write through the same approval-gated loop (already true in `core`). | Orchestrator conversation plans, shows specialist progress, edits the project under the gate |
| **5 — Polish / optional** | Vision (paste a screenshot, `describe_image`). Context-window usage bar. Persona list/editor in Settings. Optional raw-API log file. Marketplace prep (icon, `since/until-build`, publish). | Published to Marketplace |

### Open decisions

1. Reuse strategy **A** (shared `core` module) or **B** (fork into the plugin)?
2. Build: convert the Java side to Gradle, or keep `core` on Maven + consume from `mavenLocal`?
3. UI: native Swing, or JCEF webview shared with `vscode-extension/`?
4. Language: all Java, or Java `core` + Kotlin plugin glue?

---

## Repo / infra

- **`release-verify/` has no tracked content** — it's only a `.gitignore` target
  for scan output. Commit the actual SAST/SBOM scripts + config if it should be a
  real component.
- **Merge `dev` → `master` + tag** on each `desktop-java/` release (the CI
  installer matrix in `.github/workflows/desktop-java.yml` fires on `v*` tags).
