# MultiAgent

Multi-agent chat over a **local** LLM server — switchable agent personas, an
orchestrator that routes a task across specialists, approval-gated workspace
file/git tools, image (vision) input, and a side-by-side split view for comparing
two conversations. Connects to **[Lemonade](https://github.com/lemonade-sdk/lemonade)**,
any other **OpenAI-compatible server** ([NoLlama](https://github.com/aweussom/NoLlama),
LM Studio, vLLM, real OpenAI, ...), or a **native Ollama server** — save multiple
named connections and switch between them.

Nothing is bundled: you run the server yourself — [Lemonade Server](https://lemonade-server.ai/)
at `http://localhost:13305/api/v1` by default, or any other OpenAI-compatible /
Ollama server pointed to in Settings.

## Clients

| Directory | Client | Language / UI | Status |
| --- | --- | --- | --- |
| [`desktop-java/`](./desktop-java) | Desktop app | Java / JavaFX | **v1.5.4** — reference client, full feature set |
| [`vscode-extension/`](./vscode-extension) | VS Code extension | TypeScript | v1 — sidebar chat + optional workspace tools |
| [`intellij-plugin/`](./intellij-plugin) | JetBrains IDE plugin | Java core + Kotlin / Swing UI | Phase 0 — scaffold only |

Supporting: [`personas/`](./personas) — persona definitions (General, Researcher,
Coder, Critic, Orchestrator), read by every client at build time.
[`release-verify/`](./release-verify) — SAST / SBOM scan tooling for releases.

> The original Electron/React client (`desktop/`) was removed once `desktop-java/`
> passed it on features; its `shared/` TypeScript core moved into
> `vscode-extension/`, now its only consumer. Both live in git history.
> `desktop-java/` and `intellij-plugin/` are from-scratch / forked Java, not
> consumers of `shared/`.

## Feature status

✅ implemented &nbsp;·&nbsp; 🚧 partial / scaffolded &nbsp;·&nbsp; — not yet &nbsp;·&nbsp; 📋 planned (see per-client roadmap)

| Feature | desktop-java | VS Code | JetBrains |
| --- | :---: | :---: | :---: |
| Streaming chat | ✅ | ✅ | 📋 |
| Multiple named servers (Lemonade / OpenAI-compatible / Ollama) | ✅ | ✅ | 📋 |
| Per-conversation server + model pin | ✅ | — | 📋 |
| Context-window fitting + token-usage estimate | ✅ | 🚧 `maxHistory` only | 📋 |
| Personas (switchable system prompts) | ✅ pinned per conversation | ✅ app-wide | 📋 |
| In-app persona editor | ✅ | — | 📋 |
| Multi-conversation history | ✅ create / switch / delete, folders, projects | — one per workspace | 📋 |
| Conversation search + export (Markdown / JSON) | ✅ | — | 📋 |
| Message edit / regenerate | ✅ | — | 📋 |
| Workspace read tools (`list_dir` / `read_file` / `search_file`) | ✅ | 🚧 list / read | 📋 |
| Workspace write tools (`write_file` / `delete_file` / `rename_file`) | ✅ approval-gated | 🚧 write / delete | 📋 |
| Git tools (status / diff / log / show / branch / add / commit) | ✅ approval-gated | — | 📋 |
| `run_command` — sandboxed build / test | ✅ approval-gated (Phase 1) | — | 📋 |
| AI-write checkpoints: diff + revert | ✅ | — | 📋 |
| Orchestrator (plan → specialists → synthesize) | ✅ specialists write directly; model per specialist | — | 📋 |
| Vision — image attach / drag-drop / paste | ✅ inline + transcribe modes; orchestrator vision step | — | 📋 |
| `describe_image` workspace tool | ✅ | — | 📋 |
| Image generation (`generate_image`) | — schema stub, not wired | — | 📋 |
| Text-file attachments | ✅ | — | 📋 |
| Side-by-side split view | ✅ | — | — IDE splits editors |
| Themes (Light / Dark / Terminal) | ✅ | n/a host theme | n/a host theme |
| Raw API debug log | ✅ | — | 📋 |
| Packaging | ✅ Windows installer (jpackage + WiX, bundled JRE) | ✅ `.vsix` | 🚧 `buildPlugin` skeleton |

## desktop-java — the reference client

The whole ✅ column above. Multiple named server connections with per-conversation
server/model/persona pins; folder-bound workspace chats with read/write/rename +
git tools and a sandboxed `run_command`, every mutating action behind an approval
dialog and captured as a revertible checkpoint; orchestrator sessions that plan a
task, run specialists (which write to the workspace directly, each on its own
model), then synthesize; vision (attach, drag-drop, or paste an image — inline for
a multimodal chat model, a transcribe pre-pass otherwise, and a dedicated
image-examining step inside the orchestrator); project grouping over folders;
message edit/regenerate; conversation search and Markdown/JSON export;
context-window fitting with a token-usage bar; three themes; a Settings persona
editor; and an opt-in raw-API debug log. Ships as a real Windows installer
(`jpackage` + WiX, bundled JRE).

Full detail: [desktop-java/README.md](./desktop-java/README.md) ·
[desktop-java/ARCHITECTURE.md](./desktop-java/ARCHITECTURE.md).

**Roadmap** ([`TODO.md`](./TODO.md#desktop-desktop-java)):

- Quick wins: git commit-message-from-diff, pin / star a conversation, cross-folder project notes.
- Split-view "send to both panes"; command palette (Ctrl+K) navigator.
- **Model-invoked HTTP(S) fetch tool** — highest-demand item; the denylist / allowlist / approval design against SSRF + exfiltration *is* the work.
- `run_command` Phase 2 (long-running dev servers + process panel); embedded workspace terminal.
- In-app SAST / SBOM launcher; Docker tool (deferred — whole-host blast radius).
- Config: pick a default VLM for vision.

## vscode-extension

**v1 scope:** sidebar chat view; streaming over an OpenAI-compatible or native
Ollama server; persona switching (system prompt only); per-workspace history,
single conversation; multiple saved server connections with a switcher; optional
sandboxed workspace tools (`list_dir` / `read_file` / `write_file` / `delete_file`,
off by default, with an XML-tag fallback for models without tool-calling).
Package with `npm run package` → `.vsix` (no marketplace account needed).

[vscode-extension/README.md](./vscode-extension/README.md).

**Roadmap** ([`TODO.md`](./TODO.md#vs-code-extension)):

- Multi-conversation history (prerequisite for most of the rest).
- Per-conversation server selection.
- Port orchestrator mode; port image generation.
- Message edit/regenerate, conversation search/export, file-write diff/undo.
- `run_command` / build-test loop.
- Publish to Open VSX or an internal registry.

## intellij-plugin

**Phase 0 — scaffold.** Gradle + IntelliJ Platform Gradle Plugin 2.x skeleton;
the UI-free `core/` (LLM clients, workspace + git tools, `run_command`,
orchestrator, persistence, personas) forked from `desktop-java/` and stripped of
JavaFX — 58 core files + 21 tests; an empty **MultiAgent** tool window; a
storage-spike action that proves `sqlite-jdbc`'s native library loads under the
plugin classloader. Reuse strategy is **fork** (Strategy B): the plugin does not
depend on `desktop-java/`, so a core bug fix is a manual re-apply in each.

[intellij-plugin/README.md](./intellij-plugin/README.md).

**Roadmap** ([`TODO.md`](./TODO.md#jetbrains-plugin) — phases 1–5):

- **1 — Minimal chat:** streaming chat via the reused `ChatService`; server / model / persona config as an IDE `Configurable`; history via the reused `ConversationStore`; health indicator.
- **2 — Workspace chat:** wire `ToolLoopRunner` + `WorkspaceService` + `GitService` + `RunCommandService`; approval gate → `DialogWrapper`; checkpoint diff / revert via `DiffManager`; auto-bind to the open project.
- **3 — IDE-native:** editor context-menu actions (Add selection / Explain); auto-include the active file / selection; conversation list with per-conversation model / server / persona pinning; search + export; status-bar widget.
- **4 — Orchestrator:** wire `OrchestratorService` with progress in the tool window; coordinator + per-specialist model pickers.
- **5 — Polish:** vision (paste a screenshot); context-window usage bar; persona editor in Settings; Marketplace prep.

## Built-in personas

Personas are just a `name`, a one-line `description`, a `systemPrompt`, and a
`color` (a hex accent shown on message bubbles). They live as one JSON file each
in [`personas/`](./personas) and are read by every client at build time; drop a
new `personas/<id>.json` in to add one (`desktop-java/` also has an in-app editor
that writes to `%APPDATA%/MultiAgentJava/personas/`). In `desktop-java/` a persona
is pinned per conversation; `orchestrator` is offered only as an orchestrator
chat's **Coordinator**, and every other persona is a candidate specialist.

| Persona | Description | System prompt |
| --- | --- | --- |
| **General** | Helpful all-purpose assistant | *You are a helpful, concise assistant. Answer clearly, prefer short paragraphs, and ask a clarifying question when the request is ambiguous.* |
| **Researcher** | Finds structure, cites caveats, digs into details | *You are a careful researcher. Break problems into facts vs assumptions, note uncertainty, and structure answers with clear headings and bullet points. Prefer evidence-oriented reasoning over speculation.* |
| **Coder** | Writes and explains code | *You are a pragmatic software engineer. Prefer working code over theory. Explain only what matters, call out edge cases, and use fenced code blocks with language tags. Match the user's stack when known.* |
| **Critic** | Stress-tests ideas and finds weak spots | *You are a constructive critic. Challenge weak assumptions, identify risks, and suggest concrete improvements. Be direct but fair; always end with the strongest remaining path forward.* |
| **Orchestrator** | Routes work to specialists and synthesizes a final answer | *You are the Orchestrator. You coordinate specialist agents and produce a clear final answer for the user. Be decisive, concise, and faithful to specialist findings. When synthesizing, resolve conflicts, drop redundancy, and lead with the actionable answer.* |

When workspace tools, `run_command`, or the orchestrator flow are active, each
client appends its own instructions (the directory tree, the tool list, the
approval rules, the plan/specialist/synthesize framing) *after* the persona's
prompt — the JSON above is only the persona half.

## Requirements

Every client needs a local server with a chat model loaded — Lemonade by default,
or set the provider to `openai` for any other OpenAI-compatible server, or
`ollama` pointed at a native Ollama instance.

**Note:** [NoLlama](https://github.com/aweussom/NoLlama)'s OpenAI-compatible
endpoint (port 8000) works well as the `openai` provider; its Ollama-compatible
endpoint (port 11434) currently doesn't reliably honor the requested
model/prompt — see [desktop-java/ARCHITECTURE.md](./desktop-java/ARCHITECTURE.md#providers).
