# MultiAgent for JetBrains IDEs

Multi-agent chat over a local LLM server, in an IDE tool window. The fourth MultiAgent
client, alongside `../desktop-java/` (JavaFX) and `../vscode-extension/` (TypeScript).

## Independent by design

This project **does not depend on `desktop-java/`**. The core Java packages under
`src/main/java/com/multiagent/intellij/core/` — `llm/`, `workspace/`, `persistence/`,
`service/`, `model/`, `action/` — are a **deliberate copy** of `desktop-java/`'s
equivalents, renamed into `com.multiagent.intellij.core`. Strategy B ("fork"), chosen so
the two clients evolve on their own schedule and neither is pinned to the other's build.
Cost: a bug fixed in one core is a manual re-apply in the other.

The only change made to the forked code: `DebugLog` swapped its JavaFX `ObservableList`
for a plain ring + listener list, so `core` has zero UI-toolkit dependency.

**Not** carried over from `desktop-java/`: `ChatViewModel` and everything under `ui/` (the
plugin builds its UI on the IntelliJ Platform), and `App` / `Launcher` / `jpackage`.

## Layout

```
src/main/java/com/multiagent/intellij/core/   forked, UI-free (58 files)
src/main/kotlin/com/multiagent/intellij/ui/   plugin UI (Kotlin + Swing / JBUI)
src/main/resources/META-INF/plugin.xml
src/test/java/.../core/                        forked tests (21 files)
```

## Build

Needs **JDK 21** (the IntelliJ Platform build requires it; `build.gradle.kts` also pins the
toolchain to 21).

```bash
./gradlew buildPlugin    # first run downloads the target IDE (~1.5 GB)
./gradlew runIde         # sandbox IDE with the plugin loaded
./gradlew test           # runs the forked core tests
```

## Tools available to the model

Only once a conversation has a workspace bound (automatic per project since Phase 2 - see
below). Read-only tools run immediately; every other one shows a `DialogActionApprover`
approval dialog first (summary + full detail - file content, a diff, or the command line)
and is skipped if declined.

**Files** (path is always relative to the project root)
| Tool | Approval? | Notes |
| --- | --- | --- |
| `list_dir(path=".")` | no | Lists files/directories under a relative path |
| `read_file(path, offset?, limit?)` | no | Reads a UTF-8 text file; `offset`/`limit` read only a line range |
| `search_file(path, pattern, regex?, ignore_case?, context?, max_matches?)` | no | Greps one file for matching lines - use before `read_file` on anything large |
| `write_file(path, content)` | **yes** | Creates or overwrites a UTF-8 text file; creates parent folders as needed |
| `delete_file(path)` | **yes** | Deletes a single file (not directories) |
| `rename_file(path, newPath)` | **yes** | Renames/moves a file; fails if the destination exists |

`write_file`/`delete_file` each capture a checkpoint - their chat row gets **View diff** and
**Revert** buttons (see [`CheckpointService`](src/main/java/com/multiagent/intellij/core/service/CheckpointService.java)).

**Git** (only advertised when the bound folder has a `.git`)
| Tool | Approval? | Notes |
| --- | --- | --- |
| `git_status()` | no | Working-tree status: staged/unstaged/untracked |
| `git_diff(patch?, staged?, commit?, path?)` | no | Diffstat by default; `patch=true` for full hunks |
| `git_log(count?, path?)` | no | Recent commits, newest first (default 15, max 100) |
| `git_show(ref?, patch?)` | no | One commit's metadata (+ patch if `patch=true`) |
| `git_branch()` | no | Local + remote branches, marks the current one |
| `git_add(path)` | **yes** | Stages a path; `"."` stages everything |
| `git_commit(message, all?)` | **yes** | `all=true` first stages every tracked modified file (`git commit -a`) |

**Process**
| Tool | Approval? | Notes |
| --- | --- | --- |
| `run_command(command, args?, cwd?, timeout_seconds?)` | **yes** | One process, argv-only - no shell, pipes, redirects, or `cd`. Refuses a denylist of shells and destructive/privileged/network executables (`rm`, `sudo`, `bash`, `powershell`, `ssh`, `dd`, … - see [`RunCommandService.BLOCKED_EXECUTABLES`](src/main/java/com/multiagent/intellij/core/workspace/RunCommandService.java)). Default timeout 120s (max 600s, then killed); output capped at 20,000 chars |

**Not functional** - `generate_image` is advertised (schema + prompt text) but throws if
called; it's a pre-existing stub in `desktop-java` too (`LlmClient.generateImage` is a
`Phase 2/3` placeholder there), carried over as-is by the fork rather than fixed here.
`describe_image` (vision) is never added to the tool list in this client at all - see
"not worth carrying over" in [`../TODO.md`](../TODO.md#jetbrains-plugin).

## Status

**Phase 0** — done. Gradle + IntelliJ Platform Gradle Plugin 2.x skeleton; core + tests
forked and de-JavaFX'd; `runIde` verified in a real sandbox; the one real Phase-0 risk
(sqlite-jdbc under the plugin classloader) hit and fixed — `DriverManager`'s
`ServiceLoader` auto-discovery keys off the calling thread's context classloader, which
under the IntelliJ Platform isn't reliably the plugin's own `PluginClassLoader`, so
`ConversationStore` now forces registration with an explicit
`Class.forName("org.sqlite.JDBC")` static initializer.

**Phase 1** — done. `MultiAgentService` (application `@Service` owning the forked core),
`MultiAgentChatPanel` (streaming chat UI: bubbles, composer, model dropdown, health
indicator), `MultiAgentConfigurable` (Settings → Tools → MultiAgent). Verified in a real
sandbox: connects, streams a real response, and conversation history survives an IDE
restart.

**Phase 2** — done. Each project's tool window auto-binds its conversation to
`project.basePath` (no folder picker), routing it through `ToolLoopRunner`'s workspace/git/
`run_command` tools. `DialogActionApprover` (a `DialogWrapper`) gates every mutating tool
call. Tool calls render as live-updating rows in the chat; `write_file`/`delete_file` rows
get **View diff** (`DiffManager`) and **Revert** (`CheckpointService`) buttons. Successful
writes/deletes/renames and reverts refresh the VFS so the editor and Project view update
without a manual refresh. Verified in a real sandbox: approval dialog, file edits visible
live in the editor, diff, revert, and a real `run_command` subprocess run all confirmed.

**Phase 3** — code-complete. A "Chat:" picker (combo box, "⋮" menu for New/Delete) lets a
project hold several conversations, switching in place; a "Persona:" combo persists its choice onto the
conversation (`ConversationStore.setConversationPersona`), and the model combo's value is
persisted the same way; **MultiAgent: Add Selection to Chat** / **MultiAgent: Explain
Selection** editor context-menu actions (enabled only with a selection) drive the tool
window from the editor; a status-bar widget shows the last health/model check and jumps to
the tool window on click. Also fixed along the way: the Persona combo only ever showed
"General" - `PersonaRegistry`'s bundled-personas lookup needs a real filesystem directory
next to its own class's code source, which doesn't exist under `PluginClassLoader` (its
`CodeSource` is null, verified empirically) - fixed by bundling the personas as classpath
resources and seeding `PersonaRegistry`'s writable directory from them on first run. All of
the above verified in a real sandbox.

**Phase 4** — implemented and now confirmed live: **New Orchestrator** chat kind, plan →
specialists → synthesize progress rows (`onStep`), per-specialist note bubbles, the Persona
combo relabels to **Coordinator** for an orchestrator chat, and a **Specialists…** dialog sets
a per-specialist model override (`SpecialistModelsDialog`, `SpecialistModels`). A real run
against this project ("what does the architecture look like?") produced a plan → one
`researcher` specialist (an accurate, grounded summary of `build.gradle.kts` /
`settings.gradle.kts` / the module layout - not hallucinated) → a coherent synthesis, captured
via **Export as JSON** (see below). **Still unverified: a specialist actually writing a file**
- specialists write through the same approval-gated `ToolLoopRunner` a normal chat uses when a
workspace is bound (no separate opt-in), but every real run so far has been read-only.

Search ("⋮" chat menu) - `SearchDialog`, scoped to this project's own
conversations (unlike desktop-java's global topbar search - a tool window only ever cares
about one project's chats), backed directly by the forked `ConversationStore.search`. **Confirmed
live.** Export ("⋮" chat menu) - Markdown/JSON via the forked `ExportFormat`, saved
through a native `FileSaverDialog` - **confirmed live**, JSON export produced well-formed
output (conversation metadata + messages) used to verify the orchestrator run above.
Auto-context - an "Include active file" checkbox by the composer that, when checked, prepends
the editor's current selection (same format as "Add Selection") or just the open file's
relative path (no full-file dump - the model already has `read_file` once a workspace is
bound) to the next message, without a separate action per turn. This closes out Phase 3's
scope; see "Real-IDE testing" below for
what's actually been clicked through versus still sandbox/unit-test-only.

Next: manually verify Phase 4 (orchestrator) and the Phase 3 items not yet exercised (search,
export, auto-context) in a real IDE, then Phase 5 (polish) — see
[`../TODO.md`](../TODO.md#jetbrains-plugin).

### Real-IDE testing

Started via `./gradlew buildPlugin` + **Install Plugin from Disk** into a real IDE (see
"Build" above) rather than the much slower `runIde` sandbox - this is the first non-sandbox,
non-unit-test verification the plugin has had, and found real bugs `./gradlew test` couldn't
catch. **Fixed and confirmed** (screenshot evidence, both against the actual
`intellij-plugin` project as its own bound workspace):

- Message rows no longer stretch to fill the tool window's leftover vertical space (a
  `JPanel`'s default `getMaximumSize()` is unbounded regardless of content - see
  `TightRowPanel` in `MultiAgentChatPanel`).
- The model combo shows a long id's readable prefix ("Qwen2.5-Coder-7B-Instruct-G...") instead
  of its tail - took three attempts (`setModelComboText`'s doc comment has the history); the
  first two silently did nothing because `editor.editorComponent` isn't the real text field
  under IntelliJ's LaF, it just looks like it should be.
- The chat picker actually works: the chat tab strip (`JBTabbedPane` + `SCROLL_TAB_LAYOUT`)
  rendered as just its "more tabs" overflow dropdown with no visible label at all, even down
  to one sibling button - button crowding wasn't the (whole) cause, and it was never
  root-caused past that, so it was blocking actually picking a chat rather than staying a
  deferred cosmetic item. Replaced outright with a plain `JComboBox<Conversation>`
  (`ConversationRenderer` shows the title) - what Phase 3 originally shipped with before the
  UI review pass swapped it for tabs. A combo degrades to an ellipsis instead of disappearing
  entirely when it's too narrow.
- A brand-new conversation now resolves a real model instead of going out blank. Its model is
  always `NULL` (`ConversationStore.createConversation`); `switchTo()` used to only update the
  model combo when the *target* conversation already had one, leaving a fresh chat showing
  whatever the *previous* conversation's model happened to be (or blank, on the very first
  switch of a session) with nothing falling back to `AppSettings.model` ("fallback ... for
  conversations without their own"). Observed live before the fix: a **New Chat** sent with no
  model resolved produced garbled, unrelated output (Rust *and* Node.js scaffolding as raw
  unexecuted tool-call text) - not a model-quality problem, the request just went out with the
  wrong/empty model. A second, related gap surfaced once `AppSettings.model` turned out to
  *also* be blank (nothing had ever been typed into Settings' Model field): `addItem()`
  auto-selects a combo's first entry through its own internal machinery when nothing is
  known ahead of time, bypassing `setModelComboText` - and its caret fix - entirely.
  `refreshHealthAndModels()` now reads back whatever ended up selected and re-applies it
  through `setModelComboText` unconditionally, so the caret reset always runs.

**Not a plugin bug - a model-quality finding, worth keeping in mind:** with a model properly
selected, asked to `list_dir` on a real (Kotlin/Gradle) project,
**DeepSeek-Coder-V2-Lite-Instruct-GGUF-Q4_K_M** twice proposed scaffolding an unrelated Rust
project (`Cargo.toml`, `src/main.rs`, `cargo build`) that was never asked for - each
`write_file`/`run_command` was correctly declined by the approval gate, so nothing touched
disk. Swapping to **Qwen2.5-Coder-7B-Instruct-GGUF-Q4_K_M** for the identical prompt/workspace
produced a correct, on-topic answer (a clean recursive `list_dir` and nothing else) with no
unsolicited actions. Since `ToolLoopRunner` is shared with `desktop-java`, this would
reproduce there too - it's model-specific unreliability (small, heavily quantized models can
fixate on a canned response irrespective of actual context), not something to chase in this
plugin's code.
