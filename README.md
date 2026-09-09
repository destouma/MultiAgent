# MultiAgent

Multi-agent chat over a local LLM server, with switchable agent personas, orchestrator mode, workspace file/git tools, and a side-by-side split view for comparing two conversations. Connects to **[Lemonade](https://github.com/lemonade-sdk/lemonade)**, any other **OpenAI-compatible server** ([NoLlama](https://github.com/aweussom/NoLlama), LM Studio, vLLM, real OpenAI, ...), or a **native Ollama server** — save multiple named connections and switch between them in Settings.

- [`desktop-java/`](./desktop-java) — **the desktop client** (Java/JavaFX). See [desktop-java/README.md](./desktop-java/README.md) and [desktop-java/ARCHITECTURE.md](./desktop-java/ARCHITECTURE.md).
- [`vscode-extension/`](./vscode-extension) — VS Code extension. See [vscode-extension/README.md](./vscode-extension/README.md).

> The original Electron/React desktop client (`desktop/`) was removed once `desktop-java/` reached and went past feature parity with it. It lives in git history if you need it.

`vscode-extension/` shares TypeScript code with the (removed) Electron client; `desktop-java/` is a from-scratch Java port of the same ideas, not a consumer of these:

- [`shared/types.ts`](./shared/types.ts) — message, persona, and settings types
- [`shared/llm/`](./shared/llm) — `LlmClient` interface plus the `LemonadeClient` / `OpenAiClient` / `OllamaClient` implementations and the factory that picks between them
- [`shared/workspace/`](./shared/workspace) — sandboxed folder read/write tools (`list_dir`/`read_file`/`write_file`/`delete_file`) and the XML-tag fallback parser for models without native tool-calling
- [`personas/`](./personas) — persona definitions (General, Researcher, Coder, Critic, Orchestrator)

Every client requires a local server running — [Lemonade Server](https://lemonade-server.ai/) by default (`http://localhost:13305/api/v1`), or any other OpenAI-compatible / Ollama server pointed to in Settings. Nothing is bundled; you install and run the server yourself.

**Note:** [NoLlama](https://github.com/aweussom/NoLlama)'s OpenAI-compatible endpoint (port 8000) works well as the `openai` provider; its Ollama-compatible endpoint (port 11434) currently doesn't reliably honor the requested model/prompt — see [desktop-java/ARCHITECTURE.md](./desktop-java/ARCHITECTURE.md#providers) for details.
