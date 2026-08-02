# MultiAgent

Multi-agent chat over a local LLM server, with switchable agent personas, orchestrator mode, and a side-by-side split view for comparing two conversations. Connects to **[Lemonade](https://github.com/lemonade-sdk/lemonade)**, any other **OpenAI-compatible server** ([NoLlama](https://github.com/spignelon/nollama), LM Studio, vLLM, real OpenAI, ...), or a **native Ollama server** — save multiple named connections and switch between them in Settings. Two clients share the same personas, message types, provider clients, and workspace file tools:

- [`desktop/`](./desktop) — Windows desktop app (Electron + React). See [desktop/README.md](./desktop/README.md) and [desktop/ARCHITECTURE.md](./desktop/ARCHITECTURE.md).
- [`vscode-extension/`](./vscode-extension) — VS Code extension. See [vscode-extension/README.md](./vscode-extension/README.md).

Shared across both clients:

- [`shared/types.ts`](./shared/types.ts) — message, persona, and settings types
- [`shared/llm/`](./shared/llm) — `LlmClient` interface plus the `LemonadeClient` / `OpenAiClient` / `OllamaClient` implementations and the factory that picks between them
- [`shared/workspace/`](./shared/workspace) — sandboxed folder read/write tools (`list_dir`/`read_file`/`write_file`/`delete_file`) and the XML-tag fallback parser for models without native tool-calling
- [`personas/`](./personas) — persona definitions (General, Researcher, Coder, Critic, Orchestrator)

Both clients require a local server running — [Lemonade Server](https://lemonade-server.ai/) by default (`http://localhost:13305/api/v1`), or any other OpenAI-compatible / Ollama server pointed to in Settings. Nothing is bundled; you install and run the server yourself.

**Note:** [NoLlama](https://github.com/spignelon/nollama)'s OpenAI-compatible endpoint (port 8000) works well as the `openai` provider; its Ollama-compatible endpoint (port 11434) currently doesn't reliably honor the requested model/prompt — see [desktop/ARCHITECTURE.md](./desktop/ARCHITECTURE.md#providers) for details.
