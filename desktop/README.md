# MultiAgent Desktop

Windows desktop chat app for local LLM servers. Talk to models through Lemonade, any other OpenAI-compatible API (NoLlama, LM Studio, vLLM, real OpenAI, ...), or a native Ollama server — save multiple named connections and switch between them in Settings — with switchable agent personas, workspace folder tools, image sessions (OpenAI-compatible providers only), orchestrator mode, and a side-by-side split view for comparing two conversations from the same folder.

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
