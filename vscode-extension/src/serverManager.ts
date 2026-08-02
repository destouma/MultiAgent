import * as vscode from 'vscode';
import { randomUUID } from 'node:crypto';
import type { ProviderType, ServerProfile } from '../../shared/types';

const OPENAI_DEFAULT_URL = 'http://localhost:13305/api/v1';
const OLLAMA_DEFAULT_URL = 'http://localhost:11434';

function config(): vscode.WorkspaceConfiguration {
  return vscode.workspace.getConfiguration('multiagent');
}

function readServers(): ServerProfile[] {
  return config().get<ServerProfile[]>('servers', []);
}

function readActiveServerId(): string {
  return config().get<string>('activeServerId', '');
}

/** Seeds one profile from the existing flat settings so existing single-connection users don't lose their setup. */
export async function ensureDefaultServer(): Promise<void> {
  if (readServers().length) return;
  const cfg = config();
  const profile: ServerProfile = {
    id: randomUUID(),
    name: 'Default',
    providerType: cfg.get<ProviderType>('providerType', 'lemonade'),
    baseUrl: cfg.get<string>('baseUrl', OPENAI_DEFAULT_URL),
    apiKey: cfg.get<string>('apiKey', 'local-llm'),
    maxHistory: cfg.get<number>('maxHistory', 40),
  };
  await cfg.update('servers', [profile], vscode.ConfigurationTarget.Global);
  await cfg.update('activeServerId', profile.id, vscode.ConfigurationTarget.Global);
}

/** Copies a saved profile's fields into the active-connection settings, mirroring the desktop app's design. */
async function activateServer(profile: ServerProfile): Promise<void> {
  const cfg = config();
  await cfg.update('providerType', profile.providerType, vscode.ConfigurationTarget.Global);
  await cfg.update('baseUrl', profile.baseUrl, vscode.ConfigurationTarget.Global);
  await cfg.update('apiKey', profile.apiKey, vscode.ConfigurationTarget.Global);
  await cfg.update('maxHistory', profile.maxHistory, vscode.ConfigurationTarget.Global);
  await cfg.update('activeServerId', profile.id, vscode.ConfigurationTarget.Global);
}

async function promptNewServer(): Promise<ServerProfile | undefined> {
  const providerPick = await vscode.window.showQuickPick(
    [
      { label: 'Lemonade', value: 'lemonade' as ProviderType },
      {
        label: 'OpenAI-compatible (NoLlama, LM Studio, vLLM, ...)',
        value: 'openai' as ProviderType,
      },
      { label: 'Ollama', value: 'ollama' as ProviderType },
    ],
    { title: 'MultiAgent: New server (1/4) — type', placeHolder: 'Select server type' },
  );
  if (!providerPick) return undefined;

  const defaultUrl = providerPick.value === 'ollama' ? OLLAMA_DEFAULT_URL : OPENAI_DEFAULT_URL;
  const baseUrl = await vscode.window.showInputBox({
    title: 'MultiAgent: New server (2/4) — base URL',
    value: defaultUrl,
    prompt: 'Server API base URL',
  });
  if (!baseUrl) return undefined;

  const name = await vscode.window.showInputBox({
    title: 'MultiAgent: New server (3/4) — name',
    value: providerPick.label.split(' (')[0],
    prompt: 'Name for this connection',
  });
  if (!name) return undefined;

  let apiKey = 'local-llm';
  if (providerPick.value !== 'ollama') {
    apiKey =
      (await vscode.window.showInputBox({
        title: 'MultiAgent: New server (4/4) — API key',
        value: 'local-llm',
        prompt: 'API key stub (usually unused by local servers)',
      })) ?? 'local-llm';
  }

  return {
    id: randomUUID(),
    name: name.trim(),
    providerType: providerPick.value,
    baseUrl: baseUrl.trim().replace(/\/$/, ''),
    apiKey: apiKey.trim() || 'local-llm',
    maxHistory: config().get<number>('maxHistory', 40),
  };
}

type ServerQuickPickItem = vscode.QuickPickItem & { profile?: ServerProfile; addNew?: boolean };

export async function switchServer(): Promise<void> {
  const servers = readServers();
  const activeId = readActiveServerId();

  const items: ServerQuickPickItem[] = [
    ...servers.map((profile) => ({
      label: `${profile.id === activeId ? '$(check) ' : ''}${profile.name}`,
      description: profile.providerType,
      detail: profile.baseUrl,
      profile,
    })),
    { label: '$(add) Add new server...', addNew: true },
  ];

  const picked = await vscode.window.showQuickPick(items, {
    title: 'MultiAgent: Switch Server',
    placeHolder: 'Select a saved server, or add a new one',
  });
  if (!picked) return;

  if (picked.addNew) {
    const profile = await promptNewServer();
    if (!profile) return;
    await config().update('servers', [...servers, profile], vscode.ConfigurationTarget.Global);
    await activateServer(profile);
    void vscode.window.showInformationMessage(`MultiAgent: switched to "${profile.name}".`);
    return;
  }

  if (picked.profile && picked.profile.id !== activeId) {
    await activateServer(picked.profile);
    void vscode.window.showInformationMessage(`MultiAgent: switched to "${picked.profile.name}".`);
  }
}
