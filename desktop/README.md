# MultiAgent Desktop

Desktop chat app for local LLM servers (Windows installer + Linux AppImage; macOS not packaged yet). Talk to models through Lemonade, any other OpenAI-compatible API (NoLlama, LM Studio, vLLM, real OpenAI, ...), or a native Ollama server — save multiple named connections and switch between them in Settings, or pin different conversations to different servers and run them side by side. Also: switchable agent personas, workspace folder tools with diff/undo on AI file writes, image sessions (OpenAI-compatible providers only), orchestrator mode, message edit/regenerate, conversation search/export, and a token-usage estimate near the composer.

This is the Electron client. Persona definitions (`../personas/`) and shared types (`../shared/types.ts`) live one level up, at the repo root, so they can also be used by [`../vscode-extension/`](../vscode-extension/).

**Full architecture and usage guide:** see [ARCHITECTURE.md](./ARCHITECTURE.md).

## Quick start

```bash
cd desktop
npm install
npm run dev
```

Requires Lemonade at `http://localhost:13305/api/v1`.

## Package a Windows installer

Build an NSIS installer (x64) with electron-builder:

```bash
npm install
npm run pack
```

What this does:

1. `npm run build` — compiles the React UI (`dist/`) and Electron main/preload (`dist-electron/`)
2. `electron-builder --win nsis --x64` — packages the app and creates the installer

**Output:** `release/MultiAgent Setup <version>.exe`  
(Example: `release/MultiAgent Setup 1.0.0.exe` — version comes from `package.json`.)

### Requirements

- Windows x64 machine (or Windows CI runner)
- Node.js 20+
- Network access on the first pack so electron-builder can download Electron binaries

### After install

1. Install and start [Lemonade Server](https://lemonade-server.ai/) (not bundled with MultiAgent).
2. Run MultiAgent from the Start Menu / install folder.
3. Confirm the connection badge is online (default API: `http://localhost:13305/api/v1`).

### Optional: bump version

Edit `"version"` in `package.json` before packing so the installer filename and app version update.

## Package a Linux AppImage

```bash
npm install
npm run pack:linux
```

**Output:** `release/MultiAgent-<version>.AppImage` — a single self-contained executable; no install step, just `chmod +x` and run.

**Must be built on Linux** (or Linux CI, e.g. `ubuntu-latest`) — AppImage packaging relies on symlinks that Windows can't create without elevated privileges, so `npm run pack:linux` will fail partway through on a Windows machine even though the config and build steps before it are identical. No signing is required to run an AppImage locally.
