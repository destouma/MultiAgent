import { app, BrowserWindow, dialog, shell } from 'electron';
import path from 'node:path';
import { createServices, registerIpcHandlers, type AppServices } from './ipc/handlers';
import { getSettings } from './config';

const THEME_BACKGROUND = { light: '#F3F0E8', dark: '#17150F' } as const;

let mainWindow: BrowserWindow | null = null;
let services: AppServices | null = null;

process.on('uncaughtException', (error) => {
  console.error('[main] Uncaught exception:', error);
});

process.on('unhandledRejection', (reason) => {
  console.error('[main] Unhandled rejection:', reason);
});

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 840,
    minWidth: 960,
    minHeight: 640,
    title: 'MultiAgent',
    backgroundColor: THEME_BACKGROUND[getSettings().theme],
    icon: path.join(__dirname, '../build/icon.ico'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });

  mainWindow.webContents.on('render-process-gone', (_event, details) => {
    console.error('[main] Renderer process gone:', details.reason);
    if (details.reason !== 'clean-exit') {
      dialog.showErrorBox(
        'MultiAgent crashed',
        `The app window stopped responding (${details.reason}) and needs to restart.`,
      );
      mainWindow = null;
      createWindow();
    }
  });

  if (process.env.VITE_DEV_SERVER_URL) {
    mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL);
  } else {
    mainWindow.loadFile(path.join(__dirname, '../dist/index.html'));
  }

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

const gotSingleInstanceLock = app.requestSingleInstanceLock();

if (!gotSingleInstanceLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.whenReady().then(async () => {
    services = await createServices(() => mainWindow);
    registerIpcHandlers(services, () => mainWindow);
    createWindow();

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) {
        createWindow();
      }
    });
  });
}

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    services?.store.close();
    app.quit();
  }
});
