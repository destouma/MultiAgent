# MultiAgent

Multi-agent chat over a local LLM server, with switchable agent personas, orchestrator mode, workspace file/git tools, and a side-by-side split view for comparing two conversations. Connects to **[Lemonade](https://github.com/lemonade-sdk/lemonade)**, any other **OpenAI-compatible server** ([NoLlama](https://github.com/aweussom/NoLlama), LM Studio, vLLM, real OpenAI, ...), or a **native Ollama server** — save multiple named connections and switch between them in Settings.

- [`desktop-java/`](./desktop-java) — **the actively developed desktop client** (Java/JavaFX). See [desktop-java/README.md](./desktop-java/README.md) and [desktop-java/ARCHITECTURE.md](./desktop-java/ARCHITECTURE.md).
- [`vscode-extension/`](./vscode-extension) — VS Code extension. See [vscode-extension/README.md](./vscode-extension/README.md).
- [`desktop/`](./desktop) — **deprecated.** The original Electron/React desktop client. Superseded by `desktop-java/`, which has reached and gone beyond feature parity with it (project/folder grouping, git tools, an approval gate on file writes, text-file attachments, message edit/regenerate, and more that never made it back into this client). Kept in the repo for reference; not receiving new features. See [desktop/README.md](./desktop/README.md) and [desktop/ARCHITECTURE.md](./desktop/ARCHITECTURE.md).

Shared across the TypeScript clients (`desktop/`, `vscode-extension/`) - `desktop-java/` is a from-scratch Java port of the same ideas, not a consumer of these:

- [`shared/types.ts`](./shared/types.ts) — message, persona, and settings types
- [`shared/llm/`](./shared/llm) — `LlmClient` interface plus the `LemonadeClient` / `OpenAiClient` / `OllamaClient` implementations and the factory that picks between them
- [`shared/workspace/`](./shared/workspace) — sandboxed folder read/write tools (`list_dir`/`read_file`/`write_file`/`delete_file`) and the XML-tag fallback parser for models without native tool-calling
- [`personas/`](./personas) — persona definitions (General, Researcher, Coder, Critic, Orchestrator)

Every client requires a local server running — [Lemonade Server](https://lemonade-server.ai/) by default (`http://localhost:13305/api/v1`), or any other OpenAI-compatible / Ollama server pointed to in Settings. Nothing is bundled; you install and run the server yourself.

**Note:** [NoLlama](https://github.com/aweussom/NoLlama)'s OpenAI-compatible endpoint (port 8000) works well as the `openai` provider; its Ollama-compatible endpoint (port 11434) currently doesn't reliably honor the requested model/prompt — see [desktop/ARCHITECTURE.md](./desktop/ARCHITECTURE.md#providers) for details.
