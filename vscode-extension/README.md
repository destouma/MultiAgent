# MultiAgent for VS Code

Multi-agent chat over a local LLM server, in the editor sidebar. Connects to either an OpenAI-compatible server ([Lemonade](https://github.com/lemonade-sdk/lemonade) by default) or a native Ollama server, switchable via `multiagent.providerType`.

This is the VS Code client. It shares persona definitions (`../personas/`) and message types (`../shared/types.ts`) with [`../desktop/`](../desktop) — see the repo root [README.md](../README.md).

## v1 scope

- Sidebar chat view (activity bar → MultiAgent)
- Streaming chat over an OpenAI-compatible or native Ollama server
- Persona switching (system prompt only)
- Conversation history persisted per-workspace, single conversation

Not yet ported from the desktop app: orchestrator mode, workspace file tools, image generation, multi-conversation history. See [`../desktop/ARCHITECTURE.md`](../desktop/ARCHITECTURE.md) for what those look like there.

## Develop

```bash
cd vscode-extension
npm install
npm run watch
```

Press **F5** ("Run MultiAgent Extension") to launch an Extension Development Host with the build watching in the background.

## Configure

Settings → search "MultiAgent":

| Setting                   | Default                         |
| ------------------------- | ------------------------------- |
| `multiagent.providerType` | `openai`                        |
| `multiagent.baseUrl`      | `http://localhost:13305/api/v1` |
| `multiagent.apiKey`       | `lemonade`                      |
| `multiagent.model`        | _(empty — must be set)_         |
| `multiagent.maxHistory`   | `40`                            |

Requires a local server running with a chat model loaded — [Lemonade Server](https://lemonade-server.ai/) by default, or set `multiagent.providerType` to `ollama` and `multiagent.baseUrl` to your Ollama instance (default `http://localhost:11434`).

## Known limitation

Personas are read from `../personas/` at the extension's install path, which only resolves correctly when running from this repo checkout (dev / F5). Packaging as a `.vsix` (`vsce package`) needs a build step that copies `personas/` into `vscode-extension/personas` first, since a vsix can't include files from outside its own directory — not yet wired up.
