# MultiAgent Desktop (Java)

JavaFX desktop chat app for local LLM servers — a **parallel client** to [`../desktop/`](../desktop) (the Electron/React app), not a replacement for it. Talk to models through Lemonade or any other OpenAI-compatible API (NoLlama, LM Studio, vLLM, real OpenAI, ...) — save multiple named connections and switch between them in Settings, or pin different conversations to different servers and run them side by side. Also: switchable personas (pinned per conversation), workspace-folder tools (list/read/write/delete/rename) gated behind an approval dialog before any file is touched, diff/undo on AI file writes, orchestrator mode, conversation search/export, and three themes (Light/Dark/Terminal).

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

Not implemented yet — this module currently ships as a dev-run-only Maven project (`mvn javafx:run`). No installer/`jpackage` step exists; see [ARCHITECTURE.md §8](./ARCHITECTURE.md#8-develop--build).

## How this differs from `desktop/`

This is a from-scratch Java/JavaFX port, not a wrapper — most functionality is a faithful behavioral port (workspace sandboxing rules, orchestrator flow, split view, search/export, checkpoint diff/revert, theming), but a few things intentionally differ (persona is per-conversation here, file-mutating tools ask for confirmation first, Ollama and image generation aren't ported, no installer yet). See [ARCHITECTURE.md §10](./ARCHITECTURE.md#10-differences-from-the-electron-client) for the full list and reasoning.
