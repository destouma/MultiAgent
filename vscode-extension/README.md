# MultiAgent for VS Code

Multi-agent chat over a local LLM server, in the editor sidebar. Connects to [Lemonade](https://github.com/lemonade-sdk/lemonade) (default), any other OpenAI-compatible server (NoLlama, LM Studio, vLLM, ...), or a native Ollama server, switchable via `multiagent.providerType`.

This is the VS Code client. It shares persona definitions (`../personas/`) and message types (`../shared/types.ts`) with [`../desktop/`](../desktop) — see the repo root [README.md](../README.md).

## v1 scope

- Sidebar chat view (activity bar → MultiAgent)
- Streaming chat over an OpenAI-compatible or native Ollama server
- Persona switching (system prompt only)
- Conversation history persisted per-workspace, single conversation
- Optional read/write access to the open workspace folder (see below)

Not yet ported from the desktop app: orchestrator mode, image generation, multi-conversation history. See [`../desktop/ARCHITECTURE.md`](../desktop/ARCHITECTURE.md) for what those look like there.

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

### Multiple servers

Click the server icon in the Chat view's title bar (or run **MultiAgent: Switch Server** from the Command Palette) to save several connections and switch between them, mirroring the desktop app's Settings. Picking one, or adding a new one, copies its `providerType`/`baseUrl`/`apiKey`/`maxHistory` into the settings above — the active connection is always just those four flat settings, so nothing else needs to change to pick it up. Saved profiles live in `multiagent.servers`, editable directly in `settings.json` too:

```json
"multiagent.servers": [
  { "id": "...", "name": "Lemonade", "providerType": "lemonade", "baseUrl": "http://localhost:13305/api/v1", "apiKey": "local-llm", "maxHistory": 40 },
  { "id": "...", "name": "NoLlama (NPU)", "providerType": "openai", "baseUrl": "http://localhost:8000/v1", "apiKey": "local-llm", "maxHistory": 40 }
]
```

### Workspace tools

Click the folder icon in the Chat view's title bar (or run **MultiAgent: Toggle Workspace Tools**) to let the assistant read and write files in the first open workspace folder — `list_dir`, `read_file`, `write_file`, `delete_file`, scoped to that folder and unable to escape it (backed by [`../shared/workspace/workspaceService.ts`](../shared/workspace/workspaceService.ts), shared with the desktop app). Off by default (`multiagent.enableWorkspaceTools`); the Chat view shows a `📁 folder-name` pill when active, and each tool call streams as a status line (`→ read_file(...)`) before the assistant's reply. Requires a model that supports tool-calling, or falls back to an XML-tag convention the same way the desktop app does. Image generation isn't part of this yet — that still needs the desktop app's ImageService.

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
