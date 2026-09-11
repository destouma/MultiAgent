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

Next: Phase 2 (workspace-assisted chat — `ToolLoopRunner`, `WorkspaceService`,
`GitService`, `RunCommandService`, approval dialogs) — see
[`../TODO.md`](../TODO.md#jetbrains-plugin).
