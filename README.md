# MultiAgent

Windows desktop chat app for [Lemonade Server](https://github.com/lemonade-sdk/lemonade). Talk to local models through Lemonade’s OpenAI-compatible API, with switchable agent personas, workspace folder tools, image sessions, and orchestrator mode.

**Full architecture and usage guide:** see [ARCHITECTURE.md](./ARCHITECTURE.md).

## Quick start

```bash
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
