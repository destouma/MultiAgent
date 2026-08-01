import * as vscode from 'vscode';
import { randomUUID } from 'node:crypto';
import type { ChatCompletionMessageParam } from 'openai/resources/chat/completions';
import type { ChatMessage, ModelInfo, Persona, ProviderType } from '../../shared/types';
import { createLlmClient } from '../../shared/llm/createLlmClient';
import { ProviderError, type LlmClient, type ProviderSettings } from '../../shared/llm/types';
import { loadPersonas } from './personaRegistry';

const OPENAI_DEFAULT_URL = 'http://localhost:13305/api/v1';
const OLLAMA_DEFAULT_URL = 'http://localhost:11434';

type WebviewToExtensionMessage =
  | { type: 'ready' }
  | { type: 'send'; text: string; personaId: string }
  | { type: 'cancel' }
  | { type: 'checkHealth' }
  | { type: 'setModel'; model: string };

type ExtensionToWebviewMessage =
  | { type: 'init'; personas: Persona[]; personaId: string; history: ChatMessage[] }
  | { type: 'health'; ok: boolean; message: string }
  | { type: 'models'; models: ModelInfo[]; model: string }
  | { type: 'userMessage'; message: ChatMessage }
  | { type: 'token'; messageId: string; delta: string }
  | { type: 'done'; messageId: string; content: string; personaId: string }
  | { type: 'error'; messageId: string; message: string }
  | { type: 'busy'; busy: boolean };

const HISTORY_KEY = 'multiagent.history';
const PERSONA_KEY = 'multiagent.personaId';
const CONVERSATION_ID = 'default';

export class ChatViewProvider implements vscode.WebviewViewProvider {
  static readonly viewType = 'multiagent.chat';

  private view: vscode.WebviewView | undefined;
  private readonly personas: Persona[];
  private client: LlmClient;
  private abortController: AbortController | null = null;

  constructor(private readonly context: vscode.ExtensionContext) {
    this.personas = loadPersonas(context.extensionUri);
    this.client = createLlmClient(this.readProviderType(), this.readConnectionSettings());

    context.subscriptions.push(
      vscode.workspace.onDidChangeConfiguration((event) => {
        if (
          event.affectsConfiguration('multiagent.providerType') ||
          event.affectsConfiguration('multiagent.baseUrl') ||
          event.affectsConfiguration('multiagent.apiKey')
        ) {
          // Provider type may have changed, so always build a fresh client
          // rather than track exactly which field changed.
          this.client = createLlmClient(this.readProviderType(), this.readConnectionSettings());
        }
        if (event.affectsConfiguration('multiagent.model')) {
          void this.postModels();
        }
      }),
    );
  }

  resolveWebviewView(webviewView: vscode.WebviewView): void {
    this.view = webviewView;
    webviewView.webview.options = {
      enableScripts: true,
      localResourceRoots: [vscode.Uri.joinPath(this.context.extensionUri, 'dist')],
    };
    webviewView.webview.html = this.renderHtml(webviewView.webview);

    webviewView.webview.onDidReceiveMessage((message: WebviewToExtensionMessage) => {
      this.handleMessage(message).catch((error) => {
        console.error('[MultiAgent]', error);
      });
    });
  }

  private async handleMessage(message: WebviewToExtensionMessage): Promise<void> {
    switch (message.type) {
      case 'ready':
        this.postInit();
        void this.checkHealth();
        void this.postModels();
        break;
      case 'send':
        await this.sendMessage(message.text, message.personaId);
        break;
      case 'cancel':
        this.abortController?.abort();
        break;
      case 'checkHealth':
        void this.checkHealth();
        break;
      case 'setModel':
        await vscode.workspace
          .getConfiguration('multiagent')
          .update('model', message.model, vscode.ConfigurationTarget.Global);
        break;
    }
  }

  private postInit(): void {
    this.post({
      type: 'init',
      personas: this.personas,
      personaId: this.getCurrentPersonaId(),
      history: this.getHistory(),
    });
  }

  private async checkHealth(): Promise<void> {
    const health = await this.client.checkHealth();
    this.post({ type: 'health', ok: health.ok, message: health.message });
  }

  private async postModels(): Promise<void> {
    const currentModel = vscode.workspace.getConfiguration('multiagent').get<string>('model', '');
    let models: ModelInfo[];
    try {
      models = await this.client.listModels();
    } catch {
      models = [];
    }
    if (currentModel && !models.some((model) => model.id === currentModel)) {
      models = [...models, { id: currentModel }];
    }
    this.post({ type: 'models', models, model: currentModel });
  }

  private getHistory(): ChatMessage[] {
    return this.context.workspaceState.get<ChatMessage[]>(HISTORY_KEY, []);
  }

  private async setHistory(history: ChatMessage[]): Promise<void> {
    await this.context.workspaceState.update(HISTORY_KEY, history);
  }

  private getCurrentPersonaId(): string {
    const stored = this.context.workspaceState.get<string>(PERSONA_KEY);
    if (stored && this.personas.some((persona) => persona.id === stored)) {
      return stored;
    }
    return this.personas[0].id;
  }

  private async sendMessage(text: string, personaId: string): Promise<void> {
    const trimmed = text.trim();
    if (!trimmed) return;

    const persona =
      this.personas.find((candidate) => candidate.id === personaId) ?? this.personas[0];
    await this.context.workspaceState.update(PERSONA_KEY, persona.id);

    const config = vscode.workspace.getConfiguration('multiagent');
    const model = config.get<string>('model', '');
    const maxHistory = config.get<number>('maxHistory', 40);

    const assistantMessageId = randomUUID();

    if (!model) {
      this.post({
        type: 'error',
        messageId: assistantMessageId,
        message:
          'No model configured. Set "multiagent.model" in Settings to a model loaded on your local server.',
      });
      return;
    }

    const history = this.getHistory();
    const userMessage: ChatMessage = {
      id: randomUUID(),
      conversationId: CONVERSATION_ID,
      role: 'user',
      content: trimmed,
      personaId: persona.id,
      createdAt: Date.now(),
    };
    const withUser = [...history, userMessage];
    await this.setHistory(withUser);
    this.post({ type: 'userMessage', message: userMessage });

    const capped = withUser.slice(-Math.max(1, maxHistory));
    const openaiMessages: ChatCompletionMessageParam[] = [
      { role: 'system', content: persona.systemPrompt },
      ...capped
        .filter((message) => message.role === 'user' || message.role === 'assistant')
        .map((message) => ({
          role: message.role as 'user' | 'assistant',
          content: message.content,
        })),
    ];

    const controller = new AbortController();
    this.abortController = controller;
    this.post({ type: 'busy', busy: true });

    let fullContent = '';
    try {
      await this.client.ensureModelLoaded(model, { signal: controller.signal });

      for await (const delta of this.client.streamChat(openaiMessages, model, controller.signal)) {
        fullContent += delta;
        this.post({ type: 'token', messageId: assistantMessageId, delta });
      }

      const assistantMessage: ChatMessage = {
        id: assistantMessageId,
        conversationId: CONVERSATION_ID,
        role: 'assistant',
        content: fullContent,
        personaId: persona.id,
        createdAt: Date.now(),
      };
      await this.setHistory([...withUser, assistantMessage]);
      this.post({
        type: 'done',
        messageId: assistantMessageId,
        content: fullContent,
        personaId: persona.id,
      });
    } catch (error) {
      const mapped =
        error instanceof ProviderError
          ? error
          : new ProviderError('unknown', error instanceof Error ? error.message : String(error));

      if (mapped.code !== 'cancelled') {
        this.post({ type: 'error', messageId: assistantMessageId, message: mapped.message });
      }
    } finally {
      if (this.abortController === controller) {
        this.abortController = null;
      }
      this.post({ type: 'busy', busy: false });
    }
  }

  private readProviderType(): ProviderType {
    const config = vscode.workspace.getConfiguration('multiagent');
    return config.get<ProviderType>('providerType', 'lemonade');
  }

  private readConnectionSettings(): ProviderSettings {
    const config = vscode.workspace.getConfiguration('multiagent');
    const defaultUrl =
      this.readProviderType() === 'ollama' ? OLLAMA_DEFAULT_URL : OPENAI_DEFAULT_URL;
    return {
      baseUrl: config.get<string>('baseUrl', defaultUrl),
      apiKey: config.get<string>('apiKey', 'local-llm'),
    };
  }

  private post(message: ExtensionToWebviewMessage): void {
    void this.view?.webview.postMessage(message);
  }

  private renderHtml(webview: vscode.Webview): string {
    const scriptUri = webview.asWebviewUri(
      vscode.Uri.joinPath(this.context.extensionUri, 'dist', 'webview.js'),
    );
    const styleUri = webview.asWebviewUri(
      vscode.Uri.joinPath(this.context.extensionUri, 'dist', 'webview.css'),
    );
    const nonce = randomUUID().replace(/-/g, '');

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${webview.cspSource}; script-src 'nonce-${nonce}';" />
  <link href="${styleUri}" rel="stylesheet" />
  <title>MultiAgent</title>
</head>
<body>
  <div id="root"></div>
  <script nonce="${nonce}" src="${scriptUri}"></script>
</body>
</html>`;
  }
}
