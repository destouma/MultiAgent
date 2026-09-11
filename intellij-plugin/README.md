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

Next: Phase 3 (IDE-native integration — editor context-menu actions, conversation list,
status-bar widget) — see [`../TODO.md`](../TODO.md#jetbrains-plugin).
