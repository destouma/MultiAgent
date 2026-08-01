# MultiAgent

Multi-agent chat over a local LLM server, with switchable agent personas and orchestrator mode. Connects to either an **OpenAI-compatible server** ([Lemonade](https://github.com/lemonade-sdk/lemonade), [NoLlama](https://github.com/spignelon/nollama), LM Studio, vLLM, real OpenAI, ...) or a **native Ollama server** — switchable in Settings. Two clients share the same personas, message types, and provider clients:

- [`desktop/`](./desktop) — Windows desktop app (Electron + React). See [desktop/README.md](./desktop/README.md) and [desktop/ARCHITECTURE.md](./desktop/ARCHITECTURE.md).
- [`vscode-extension/`](./vscode-extension) — VS Code extension. See [vscode-extension/README.md](./vscode-extension/README.md).

Shared across both clients:

- [`shared/types.ts`](./shared/types.ts) — message, persona, and settings types
- [`shared/llm/`](./shared/llm) — `LlmClient` interface plus the `OpenAiClient` / `OllamaClient` implementations and the factory that picks between them
- [`personas/`](./personas) — persona definitions (General, Researcher, Coder, Critic, Orchestrator)

Both clients require a local server running — [Lemonade Server](https://lemonade-server.ai/) by default (`http://localhost:13305/api/v1`), or any other OpenAI-compatible / Ollama server pointed to in Settings. Nothing is bundled; you install and run the server yourself.

**Note:** [NoLlama](https://github.com/spignelon/nollama)'s OpenAI-compatible endpoint (port 8000) works well as the `openai` provider; its Ollama-compatible endpoint (port 11434) currently doesn't reliably honor the requested model/prompt — see [desktop/ARCHITECTURE.md](./desktop/ARCHITECTURE.md#providers) for details.
