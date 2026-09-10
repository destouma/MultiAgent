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
toolchain to 21). The Gradle wrapper jar isn't committed yet — materialise it once with a
JDK-21 Gradle (or IntelliJ's bundled Gradle):

```bash
gradle wrapper          # writes gradlew + gradle/wrapper/gradle-wrapper.jar
./gradlew buildPlugin    # first run downloads the target IDE (~1.5 GB)
./gradlew runIde         # sandbox IDE with the plugin loaded
./gradlew test           # runs the forked core tests
```

## Status — Phase 0

- [x] Gradle + IntelliJ Platform Gradle Plugin 2.x skeleton
- [x] Core + tests forked and de-JavaFX'd
- [x] Empty `MultiAgent` tool window (`MultiAgentToolWindowFactory`)
- [x] **Tools → MultiAgent: Storage Spike** — round-trips a `ConversationStore` row under
      the plugin config dir, to prove sqlite-jdbc's native lib loads under the plugin
      classloader (the one real Phase-0 risk)
- [ ] `runIde` verified (pending JDK 21 + SDK download)

Next: Phase 1 (minimal streaming chat) — see [`../TODO.md`](../TODO.md#jetbrains-plugin).
