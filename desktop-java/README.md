# MultiAgent Desktop (Java)

JavaFX desktop chat app for local LLM servers — **the actively developed desktop client**; [`../desktop/`](../desktop) (the original Electron/React app) is now deprecated, kept for reference only. Talk to models through Lemonade, any other OpenAI-compatible API (NoLlama, LM Studio, vLLM, real OpenAI, ...), or a native Ollama server — save multiple named connections and switch between them in Settings, or pin different conversations to different servers and run them side by side. Also: switchable personas (pinned per conversation), workspace-folder tools (list/read/write/delete/rename + git status/diff/log/show/branch/add/commit) gated behind an approval dialog before any mutating action runs, project grouping over folders, text-file attachments, message edit/regenerate, a token-usage estimate, diff/undo on AI file writes, orchestrator mode, conversation search/export, and three themes (Light/Dark/Terminal).

Persona definitions (`../personas/`) are shared with the Electron client and copied into this module's build output — both clients read the same JSON files, just via different code paths.

**Full architecture and usage guide:** see [ARCHITECTURE.md](./ARCHITECTURE.md).

## Quick start

Requires JDK 21+ and Maven, and a running Lemonade (or other OpenAI-compatible) server.

```bash
cd desktop-java
mvn javafx:run
```

Requires Lemonade at `http://localhost:13305/api/v1` by default (or point Settings at any OpenAI-compatible server).

### In IntelliJ

Open `desktop-java/pom.xml` as a project — no separate setup needed. A shared run configuration is included (`.run/MultiAgent (javafx-run).run.xml`, goal `javafx:run`); use it, or run `com.multiagent.desktop.App` directly.

## Tests

```bash
mvn test
```

## Packaging

Produces a real Windows installer via `jpackage` (bundled with the JDK) + WiX Toolset:

```bash
mvn clean package
jpackage --type exe --name MultiAgent --app-version 1.0.0 --vendor MultiAgent \
  --input target/jpackage-input --main-jar multiagent-desktop.jar \
  --main-class com.multiagent.desktop.Launcher --icon ../desktop/build/icon.ico \
  --dest target/dist --win-menu --win-shortcut --win-dir-chooser
```

Output: `target/dist/MultiAgent-<version>.exe`. Needs WiX Toolset v3-v5 on `PATH` — **use v5, not v6/v7** (v6+ gate builds behind an "Open Source Maintenance Fee" EULA; v5 doesn't). See [ARCHITECTURE.md §8](./ARCHITECTURE.md#8-develop--build) for the full explanation (including why the main class is `Launcher`, not `App`) and setup steps. Linux/macOS packaging isn't done yet.

## How this differs from `desktop/`

This is a from-scratch Java/JavaFX port, not a wrapper — most functionality is a faithful behavioral port (workspace sandboxing rules, orchestrator flow, split view, search/export, checkpoint diff/revert, theming, all three LLM providers including Ollama), but a few things intentionally differ (persona is per-conversation here, file-mutating tools ask for confirmation first, image generation isn't ported). See [ARCHITECTURE.md §10](./ARCHITECTURE.md#10-differences-from-the-electron-client) for the full list and reasoning.
