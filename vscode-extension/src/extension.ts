import * as vscode from 'vscode';
import { ChatViewProvider } from './chatViewProvider';
import { ensureDefaultServer, switchServer } from './serverManager';

async function toggleWorkspaceTools(): Promise<void> {
  const config = vscode.workspace.getConfiguration('multiagent');
  const current = config.get<boolean>('enableWorkspaceTools', false);
  if (!current && !vscode.workspace.workspaceFolders?.length) {
    void vscode.window.showWarningMessage(
      'MultiAgent: open a folder first to enable workspace tools.',
    );
    return;
  }
  const next = !current;
  await config.update('enableWorkspaceTools', next, vscode.ConfigurationTarget.Global);
  void vscode.window.showInformationMessage(
    `MultiAgent: workspace tools ${next ? 'enabled' : 'disabled'}.`,
  );
}

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  await ensureDefaultServer();

  const provider = new ChatViewProvider(context);
  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider(ChatViewProvider.viewType, provider),
    vscode.commands.registerCommand('multiagent.switchServer', () => void switchServer()),
    vscode.commands.registerCommand(
      'multiagent.toggleWorkspaceTools',
      () => void toggleWorkspaceTools(),
    ),
  );
}

export function deactivate(): void {}
