# MultiAgent for VS Code

Multi-agent chat over a local LLM server, in the editor sidebar. Connects to [Lemonade](https://github.com/lemonade-sdk/lemonade) (default), any other OpenAI-compatible server (NoLlama, LM Studio, vLLM, ...), or a native Ollama server, switchable via `multiagent.providerType`.

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
| `multiagent.providerType` | `lemonade`                      |
| `multiagent.baseUrl`      | `http://localhost:13305/api/v1` |
| `multiagent.apiKey`       | `local-llm`                     |
| `multiagent.model`        | _(empty — must be set)_         |
| `multiagent.maxHistory`   | `40`                            |

Requires a local server running with a chat model loaded — [Lemonade Server](https://lemonade-server.ai/) by default, or set `multiagent.providerType` to `openai` for any other OpenAI-compatible server, or `ollama` with `multiagent.baseUrl` pointed at your Ollama instance (default `http://localhost:11434`).

## Package

```bash
cd vscode-extension
npm install
npm run package
```

Produces `multiagent-vscode-<version>.vsix`. `esbuild.js` copies the repo-root `personas/*.json` into `vscode-extension/personas/` as part of the build (a `.vsix` can only contain files from inside its own directory, so this is what makes personas resolve once installed, not just when running from this repo checkout via F5). Install it with:

```bash
code --install-extension multiagent-vscode-<version>.vsix
```

or via "Install from VSIX..." in the Extensions view. No publisher account or marketplace listing needed for this — `npm run package` / manual install is enough for private or personal use. Publishing to the VS Marketplace or Open VSX is a separate step (`vsce publish` / `ovsx publish`) that additionally needs a registered publisher account.
