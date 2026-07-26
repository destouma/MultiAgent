import type { BrowserWindow } from 'electron';
import type { ModelStatusEvent } from '../../../shared/types';

export function emitModelStatus(
  getWindow: () => BrowserWindow | null,
  model: string,
  message: string,
): void {
  const lower = message.toLowerCase();
  const phase: ModelStatusEvent['phase'] = lower.includes('ready')
    ? 'ready'
    : lower.includes('loading') || lower.includes('waiting')
      ? 'loading'
      : 'checking';
  const event: ModelStatusEvent = { model, phase, message };
  getWindow()?.webContents.send('model:status', event);
}
