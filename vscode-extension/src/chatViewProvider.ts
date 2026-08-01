import * as vscode from 'vscode';
import { randomUUID } from 'node:crypto';
import * as path from 'node:path';
import type {
  ChatCompletionMessageParam,
  ChatCompletionTool,
} from 'openai/resources/chat/completions';
import type { ChatMessage, ModelInfo, Persona, ProviderType } from '../../shared/types';
import { createLlmClient } from '../../shared/llm/createLlmClient';
import { ProviderError, type LlmClient, type ProviderSettings } from '../../shared/llm/types';
import { parseActionTags } from '../../shared/workspace/actionTags';
import {
  WorkspaceError,
  WorkspaceService,
  workspaceTools,
} from '../../shared/workspace/workspaceService';
import { loadPersonas } from './personaRegistry';

const OPENAI_DEFAULT_URL = 'http://localhost:13305/api/v1';
const OLLAMA_DEFAULT_URL = 'http://localhost:11434';
const MAX_TOOL_ROUNDS = 8;
// generate_image needs ImageService, which isn't ported to the extension yet.
const EXTENSION_TOOLS = workspaceTools.filter(
  (tool) => tool.function.name !== 'generate_image',
) as ChatCompletionTool[];

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
  | { type: 'busy'; busy: boolean }
  | { type: 'workspaceStatus'; enabled: boolean; folderName: string | null }
  | {
      type: 'workspaceOp';
      op: string;
      path: string;
      status: 'running' | 'ok' | 'error';
      detail?: string;
    };

const HISTORY_KEY = 'multiagent.history';
const PERSONA_KEY = 'multiagent.personaId';
const CONVERSATION_ID = 'default';

export class ChatViewProvider implements vscode.WebviewViewProvider {
  static readonly viewType = 'multiagent.chat';

  private view: vscode.WebviewView | undefined;
  private readonly personas: Persona[];
  private readonly workspace = new WorkspaceService();
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
        if (event.affectsConfiguration('multiagent.enableWorkspaceTools')) {
          this.postWorkspaceStatus();
        }
      }),
      vscode.workspace.onDidChangeWorkspaceFolders(() => this.postWorkspaceStatus()),
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
        this.postWorkspaceStatus();
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

  private workspaceRoot(): string | null {
    return vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? null;
  }

  private workspaceToolsEnabled(): boolean {
    return vscode.workspace
      .getConfiguration('multiagent')
      .get<boolean>('enableWorkspaceTools', false);
  }

  private postWorkspaceStatus(): void {
    const root = this.workspaceRoot();
    this.post({
      type: 'workspaceStatus',
      enabled: this.workspaceToolsEnabled() && root !== null,
      folderName: root ? path.basename(root) : null,
    });
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
    const workspaceRoot = this.workspaceToolsEnabled() ? this.workspaceRoot() : null;

    const systemParts = [persona.systemPrompt];
    if (workspaceRoot) {
      let tree: string;
      try {
        tree = this.workspace.buildTree(workspaceRoot);
      } catch (error) {
        tree = error instanceof Error ? error.message : String(error);
      }

      systemParts.push(
        [
          `You have a writable workspace folder bound to this chat: ${workspaceRoot}`,
          'You may inspect and modify files inside this folder only.',
          'Prefer tools when available. If tools are unavailable, emit exact XML actions:',
          '<list_dir path="." />',
          '<read_file path="relative/path.ext" />',
          '<write_file path="relative/path.ext">FULL FILE CONTENT</write_file>',
          '<delete_file path="relative/path.ext" />',
          'After file work, give a short summary of what changed.',
          'Current workspace tree:',
          tree,
        ].join('\n'),
      );
    }

    const openaiMessages: ChatCompletionMessageParam[] = [
      { role: 'system', content: systemParts.join('\n\n') },
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

      if (workspaceRoot) {
        fullContent = await this.runWorkspaceAgent({
          workspaceRoot,
          model,
          messages: openaiMessages,
          messageId: assistantMessageId,
          signal: controller.signal,
        });
      } else {
        for await (const delta of this.client.streamChat(
          openaiMessages,
          model,
          controller.signal,
        )) {
          fullContent += delta;
          this.post({ type: 'token', messageId: assistantMessageId, delta });
        }
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

  private async runWorkspaceAgent(input: {
    workspaceRoot: string;
    model: string;
    messages: ChatCompletionMessageParam[];
    messageId: string;
    signal: AbortSignal;
  }): Promise<string> {
    const messages = [...input.messages];
    let finalText = '';
    let toolsEnabled = true;

    for (let round = 0; round < MAX_TOOL_ROUNDS; round += 1) {
      if (input.signal.aborted) {
        throw new ProviderError('cancelled', 'Generation cancelled');
      }

      let completion;
      try {
        completion = await this.client.completeChat(messages, input.model, {
          tools: toolsEnabled ? EXTENSION_TOOLS : undefined,
          signal: input.signal,
        });
      } catch (error) {
        if (toolsEnabled) {
          toolsEnabled = false;
          completion = await this.client.completeChat(messages, input.model, {
            signal: input.signal,
          });
        } else {
          throw error;
        }
      }

      const nativeCalls = completion.toolCalls;
      if (nativeCalls.length) {
        messages.push({
          role: 'assistant',
          content: completion.content || null,
          tool_calls: nativeCalls.map((call) => ({
            id: call.id,
            type: 'function' as const,
            function: { name: call.name, arguments: call.arguments },
          })),
        });

        for (const call of nativeCalls) {
          const result = await this.runToolAndEmit({
            workspaceRoot: input.workspaceRoot,
            messageId: input.messageId,
            name: call.name,
            rawArgs: call.arguments,
          });
          messages.push({ role: 'tool', tool_call_id: call.id, content: result });
        }
        continue;
      }

      const actions = parseActionTags(completion.content || '');
      if (actions.length) {
        messages.push({ role: 'assistant', content: completion.content || '' });

        const results: string[] = [];
        for (const action of actions) {
          const result = await this.runToolAndEmit({
            workspaceRoot: input.workspaceRoot,
            messageId: input.messageId,
            name: action.name,
            rawArgs: JSON.stringify(action.args),
          });
          results.push(`[${action.name}] ${result}`);
        }

        messages.push({
          role: 'user',
          content:
            'Tool results:\n' +
            results.join('\n\n') +
            '\n\nContinue. If more file work is needed, emit more actions. Otherwise summarize for the user.',
        });
        continue;
      }

      finalText = (completion.content || '').trim();
      break;
    }

    if (!finalText) {
      finalText = 'Finished workspace operations.';
    }

    this.post({ type: 'token', messageId: input.messageId, delta: finalText });
    return finalText;
  }

  private async runToolAndEmit(input: {
    workspaceRoot: string;
    messageId: string;
    name: string;
    rawArgs: string;
  }): Promise<string> {
    let args: Record<string, unknown>;
    try {
      args = JSON.parse(input.rawArgs || '{}') as Record<string, unknown>;
    } catch {
      args = {};
    }

    const relPath = String(args.path ?? '.');

    if (input.name === 'generate_image') {
      const message = 'Image generation is not supported in the VS Code extension yet.';
      this.post({
        type: 'workspaceOp',
        op: input.name,
        path: relPath,
        status: 'error',
        detail: message,
      });
      return `Error: ${message}`;
    }

    this.post({ type: 'workspaceOp', op: input.name, path: relPath, status: 'running' });

    try {
      const result = this.workspace.executeTool(input.workspaceRoot, input.name, args);
      this.post({
        type: 'workspaceOp',
        op: input.name,
        path: relPath,
        status: 'ok',
        detail: result.slice(0, 240),
      });
      return result;
    } catch (error) {
      const message =
        error instanceof WorkspaceError || error instanceof Error ? error.message : String(error);
      this.post({
        type: 'workspaceOp',
        op: input.name,
        path: relPath,
        status: 'error',
        detail: message,
      });
      return `Error: ${message}`;
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
