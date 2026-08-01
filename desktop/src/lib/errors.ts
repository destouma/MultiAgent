// Electron prefixes errors thrown from ipcMain.handle with
// "Error invoking remote method 'x': " before rejecting the invoke()
// promise in the renderer. Strip it so error messages shown to the user
// are the same clean text regardless of which IPC path produced them.
export function cleanErrorMessage(error: unknown, fallback: string): string {
  const message = error instanceof Error ? error.message : fallback;
  return message.replace(/^Error invoking remote method '[^']*':\s*/, '');
}
