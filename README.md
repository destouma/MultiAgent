# MultiAgent

Multi-agent chat over a local [Lemonade Server](https://github.com/lemonade-sdk/lemonade) (OpenAI-compatible API), with switchable agent personas and orchestrator mode. Two clients share the same personas and message types:

- [`desktop/`](./desktop) — Windows desktop app (Electron + React). See [desktop/README.md](./desktop/README.md) and [desktop/ARCHITECTURE.md](./desktop/ARCHITECTURE.md).
- [`vscode-extension/`](./vscode-extension) — VS Code extension. See [vscode-extension/README.md](./vscode-extension/README.md).

Shared across both clients:

- [`shared/types.ts`](./shared/types.ts) — message, persona, and settings types
- [`personas/`](./personas) — persona definitions (General, Researcher, Coder, Critic, Orchestrator)

Both clients require [Lemonade Server](https://lemonade-server.ai/) running locally (default `http://localhost:13305/api/v1`) — it is not bundled.
