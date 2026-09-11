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
| Streaming chat | ✅ | ✅ | ✅ |
| Multiple named servers (Lemonade / OpenAI-compatible / Ollama) | ✅ | ✅ | 🚧 one active connection, no saved profiles |
| Per-conversation server + model pin | ✅ | — | — |
| Context-window fitting + token-usage estimate | ✅ | 🚧 `maxHistory` only | — |
| Personas (switchable system prompts) | ✅ pinned per conversation | ✅ app-wide | 🚧 first persona only, not switchable yet |
| In-app persona editor | ✅ | — | — |
| Multi-conversation history | ✅ create / switch / delete, folders, projects | — one per workspace | 🚧 one per project |
| Conversation search + export (Markdown / JSON) | ✅ | — | 📋 |
| Message edit / regenerate | ✅ | — | 📋 |
| Workspace read tools (`list_dir` / `read_file` / `search_file`) | ✅ | 🚧 list / read | ✅ |
| Workspace write tools (`write_file` / `delete_file` / `rename_file`) | ✅ approval-gated | 🚧 write / delete | ✅ approval-gated |
| Git tools (status / diff / log / show / branch / add / commit) | ✅ approval-gated | — | ✅ approval-gated |
| `run_command` — sandboxed build / test | ✅ approval-gated (Phase 1) | — | ✅ approval-gated |
| AI-write checkpoints: diff + revert | ✅ | — | ✅ native `DiffManager` diff |
| Orchestrator (plan → specialists → synthesize) | ✅ specialists write directly; model per specialist | — | 📋 |
| Vision — image attach / drag-drop / paste | ✅ inline + transcribe modes; orchestrator vision step | — | — not planned (see intellij-plugin section) |
| `describe_image` workspace tool | ✅ | — | — not planned |
| Image generation (`generate_image`) | — schema stub, not wired | — filtered out | — schema stub, not wired |
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

**Phases 0–2 done.** The UI-free `core/` (LLM clients, workspace + git tools,
`run_command`, orchestrator, persistence, personas) forked from `desktop-java/`
and stripped of JavaFX — 58 core files + 21 tests, all passing under Gradle.
Each project's **MultiAgent** tool window auto-binds its conversation to that
project's folder and streams chat through the same workspace/git/`run_command`
tools as `desktop-java/`, gated by a `DialogWrapper` approval dialog, with
checkpoint diff/revert via the IDE's native `DiffManager` and VFS refresh so
edits show up in the editor immediately. Reuse strategy is **fork** (Strategy
B): the plugin does not depend on `desktop-java/`, so a core bug fix is a
manual re-apply in each.

[intellij-plugin/README.md](./intellij-plugin/README.md).

**Roadmap** ([`TODO.md`](./TODO.md#jetbrains-plugin) — phases 3–5):

- **3 — IDE-native:** editor context-menu actions (Add selection / Explain); auto-include the active file / selection; conversation list with per-conversation model / server / persona pinning; search + export; status-bar widget.
- **4 — Orchestrator:** wire `OrchestratorService` with progress in the tool window; coordinator + per-specialist model pickers.
- **5 — Polish:** context-window usage bar; persona editor in Settings; Marketplace prep. (Vision was dropped from the plan — see `TODO.md`'s "not worth carrying over".)

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

## Agent tools

One command surface — same names, parameters, and approval rules — shared by all
three clients, so a persona's instructions and a user's request behave the same
regardless of which one answers. Each client currently implements a different
subset (see the Feature status table above); `vscode-extension` and
`intellij-plugin` are catching up to `desktop-java`'s full set, not diverging
from it.

| Tool | Approval? | Description |
| --- | --- | --- |
| `list_dir(path)` | no | List files and directories under a relative path (`"."` = root) |
| `read_file(path, offset?, limit?)` | no | Read a UTF-8 text file; `offset`/`limit` read only a line range |
| `search_file(path, pattern, regex?, ignore_case?, context?, max_matches?)` | no | Grep one file for matching lines with line numbers — use before `read_file` on anything large |
| `write_file(path, content)` | **yes** | Create or overwrite a UTF-8 text file; creates parent folders as needed |
| `delete_file(path)` | **yes** | Delete a single file (not directories) |
| `rename_file(path, newPath)` | **yes** | Rename/move a file; fails if the destination already exists |
| `git_status()` | no | Working-tree status: staged / unstaged / untracked |
| `git_diff(patch?, staged?, commit?, path?)` | no | Diffstat by default; `patch=true` for full hunks |
| `git_log(count?, path?)` | no | Recent commits, newest first |
| `git_show(ref?, patch?)` | no | One commit's metadata (+ patch if requested) |
| `git_branch()` | no | Local + remote branches, marks the current one |
| `git_add(path)` | **yes** | Stage a path; `"."` stages everything |
| `git_commit(message, all?)` | **yes** | Record a commit; `all=true` stages every tracked modified file first |
| `run_command(command, args?, cwd?, timeout_seconds?)` | **yes** | Run one build/test/lint/run command — argv-only, no shell/pipes/redirects/`cd`; refuses a denylist of shells and destructive/privileged/network executables; timeout- and output-capped |
| `describe_image(path, question)` | no | Ask the vision model about a workspace image |
| `generate_image(prompt, path, size?)` | — | In `desktop-java`'s and `intellij-plugin`'s tool schema (`vscode-extension` filters it out); not wired to an actual implementation in any client yet — needs an `ImageService` |

`write_file`/`delete_file` calls capture a checkpoint (diff + one-shot revert) wherever
that's implemented. Every other mutating tool — `write_file`, `delete_file`,
`rename_file`, `git_add`, `git_commit`, `run_command` — is gated behind the user's
approval before it runs.

## Requirements

Every client needs a local server with a chat model loaded — Lemonade by default,
or set the provider to `openai` for any other OpenAI-compatible server, or
`ollama` pointed at a native Ollama instance.

**Note:** [NoLlama](https://github.com/aweussom/NoLlama)'s OpenAI-compatible
endpoint (port 8000) works well as the `openai` provider; its Ollama-compatible
endpoint (port 11434) currently doesn't reliably honor the requested
model/prompt — see [desktop-java/ARCHITECTURE.md](./desktop-java/ARCHITECTURE.md#providers).
