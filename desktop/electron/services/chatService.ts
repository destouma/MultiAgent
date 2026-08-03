import { randomUUID } from 'node:crypto';
import type { BrowserWindow } from 'electron';
import type {
  ChatCompletionMessageParam,
  ChatCompletionTool,
} from 'openai/resources/chat/completions';
import type {
  AppSettings,
  ChatDoneEvent,
  ChatErrorEvent,
  ChatSendRequest,
  ChatTokenEvent,
  ServerProfile,
  WorkspaceOpEvent,
} from '../../../shared/types';
import { ProviderError, type LlmClient } from '../../../shared/llm/types';
import { parseActionTags } from '../../../shared/workspace/actionTags';
import {
  WorkspaceError,
  WorkspaceService,
  workspaceTools,
} from '../../../shared/workspace/workspaceService';
import { ConversationStore } from './conversationStore';
import { PersonaRegistry } from './personaRegistry';
import { ImageService } from './imageService';
import { OrchestratorService } from './orchestratorService';
import { emitModelStatus } from './modelStatus';

const MAX_TOOL_ROUNDS = 8;

export class ChatService {
  private abortControllers = new Map<string, AbortController>();
  private activeMessageId: string | null = null;
  private workspace = new WorkspaceService();
  private orchestrator: OrchestratorService;

  constructor(
    private getClientFor: (serverId: string | null) => LlmClient,
    private store: ConversationStore,
    private personas: PersonaRegistry,
    private images: ImageService,
    private getSettings: () => AppSettings,
    private getWindow: () => BrowserWindow | null,
  ) {
    this.orchestrator = new OrchestratorService(
      getClientFor,
      store,
      personas,
      getSettings,
      getWindow,
    );
  }

  private resolveProfile(serverId: string | null): ServerProfile | null {
    if (!serverId) return null;
    return this.getSettings().servers.find((server) => server.id === serverId) ?? null;
  }

  async send(
    request: ChatSendRequest,
  ): Promise<{ userMessageId: string; assistantMessageId: string }> {
    const existing = this.store.getConversation(request.conversationId);
    if (existing?.kind === 'orchestrator') {
      this.abortControllers.get(request.conversationId)?.abort();
      this.abortControllers.delete(request.conversationId);
      return this.orchestrator.send(request);
    }

    this.abortControllers.get(request.conversationId)?.abort();
    this.abortControllers.delete(request.conversationId);

    const conversation = existing;
    if (!conversation) {
      throw new ProviderError('unknown', 'Conversation not found');
    }

    const settings = this.getSettings();
    const profile = this.resolveProfile(conversation.serverId);
    const persona = this.personas.get(request.personaId) ?? this.personas.list()[0];
    const model = persona.defaultModel || conversation.model || settings.model;

    if (!model) {
      throw new ProviderError(
        'model_not_loaded',
        'No model selected. Choose a model in the top bar or Settings.',
      );
    }

    const userMessage = this.store.addMessage({
      conversationId: request.conversationId,
      role: 'user',
      content: request.content,
    });

    const assistantMessageId = randomUUID();
    this.activeMessageId = assistantMessageId;

    const history = this.store.getMessages(request.conversationId);
    const capped = history.slice(-Math.max(1, profile?.maxHistory ?? settings.maxHistory));
    const workspacePath = conversation.workspacePath;

    const systemParts = [persona.systemPrompt];
    if (workspacePath) {
      let tree: string;
      try {
        tree = this.workspace.buildTree(workspacePath);
      } catch (error) {
        tree = error instanceof Error ? error.message : String(error);
      }

      systemParts.push(
        [
          `You have a writable workspace folder bound to this chat: ${workspacePath}`,
          'You may inspect and modify files inside this folder only.',
          'Prefer tools when available. If tools are unavailable, emit exact XML actions:',
          '<list_dir path="." />',
          '<read_file path="relative/path.ext" />',
          '<write_file path="relative/path.ext">FULL FILE CONTENT</write_file>',
          '<delete_file path="relative/path.ext" />',
          '<generate_image path="images/out.png" prompt="a red circle" size="512x512" />',
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

    const win = this.getWindow();
    const myController = new AbortController();
    this.abortControllers.set(request.conversationId, myController);
    const client = this.getClientFor(conversation.serverId);
    let fullContent = '';

    try {
      await client.ensureModelLoaded(model, {
        signal: myController.signal,
        onStatus: (message) => emitModelStatus(this.getWindow, model, message),
      });

      if (workspacePath) {
        fullContent = await this.runWorkspaceAgent({
          workspacePath,
          model,
          messages: openaiMessages,
          conversationId: request.conversationId,
          messageId: assistantMessageId,
          signal: myController.signal,
          serverId: conversation.serverId,
        });
      } else {
        for await (const delta of client.streamChat(openaiMessages, model, myController.signal)) {
          fullContent += delta;
          this.emitToken(request.conversationId, assistantMessageId, delta);
        }
      }

      this.store.addMessage({
        id: assistantMessageId,
        conversationId: request.conversationId,
        role: 'assistant',
        content: fullContent,
        personaId: persona.id,
      });

      const doneEvent: ChatDoneEvent = {
        conversationId: request.conversationId,
        messageId: assistantMessageId,
        content: fullContent,
        personaId: persona.id,
      };
      win?.webContents.send('chat:done', doneEvent);

      return { userMessageId: userMessage.id, assistantMessageId };
    } catch (error) {
      const mapped =
        error instanceof ProviderError
          ? error
          : new ProviderError('unknown', error instanceof Error ? error.message : String(error));

      if (mapped.code !== 'cancelled' && fullContent) {
        this.store.addMessage({
          id: assistantMessageId,
          conversationId: request.conversationId,
          role: 'assistant',
          content: fullContent,
          personaId: persona.id,
        });
      }

      const errorEvent: ChatErrorEvent = {
        conversationId: request.conversationId,
        messageId: assistantMessageId,
        code: mapped.code,
        message: mapped.message,
      };
      win?.webContents.send('chat:error', errorEvent);

      if (mapped.code !== 'cancelled') {
        throw mapped;
      }

      return { userMessageId: userMessage.id, assistantMessageId };
    } finally {
      if (this.abortControllers.get(request.conversationId) === myController) {
        this.abortControllers.delete(request.conversationId);
      }
      if (this.activeMessageId === assistantMessageId) {
        this.activeMessageId = null;
      }
    }
  }

  private async runWorkspaceAgent(input: {
    workspacePath: string;
    model: string;
    messages: ChatCompletionMessageParam[];
    conversationId: string;
    messageId: string;
    signal: AbortSignal;
    serverId: string | null;
  }): Promise<string> {
    const client = this.getClientFor(input.serverId);
    const messages = [...input.messages];
    let finalText = '';
    let toolsEnabled = true;

    for (let round = 0; round < MAX_TOOL_ROUNDS; round += 1) {
      if (input.signal.aborted) {
        throw new ProviderError('cancelled', 'Generation cancelled');
      }

      let completion;
      try {
        completion = await client.completeChat(messages, input.model, {
          tools: toolsEnabled ? (workspaceTools as ChatCompletionTool[]) : undefined,
          signal: input.signal,
        });
      } catch (error) {
        if (toolsEnabled) {
          toolsEnabled = false;
          completion = await client.completeChat(messages, input.model, {
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
            workspacePath: input.workspacePath,
            conversationId: input.conversationId,
            messageId: input.messageId,
            name: call.name,
            rawArgs: call.arguments,
            serverId: input.serverId,
          });
          messages.push({
            role: 'tool',
            tool_call_id: call.id,
            content: result,
          });
        }
        continue;
      }

      const actions = parseActionTags(completion.content || '');
      if (actions.length) {
        messages.push({
          role: 'assistant',
          content: completion.content || '',
        });

        const results: string[] = [];
        for (const action of actions) {
          const result = await this.runToolAndEmit({
            workspacePath: input.workspacePath,
            conversationId: input.conversationId,
            messageId: input.messageId,
            name: action.name,
            rawArgs: JSON.stringify(action.args),
            serverId: input.serverId,
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

    this.emitToken(input.conversationId, input.messageId, finalText);
    return finalText;
  }

  private async runToolAndEmit(input: {
    workspacePath: string;
    conversationId: string;
    messageId: string;
    name: string;
    rawArgs: string;
    serverId: string | null;
  }): Promise<string> {
    let args: Record<string, unknown>;
    try {
      args = JSON.parse(input.rawArgs || '{}') as Record<string, unknown>;
    } catch {
      args = {};
    }

    const relPath = String(
      args.path ?? (input.name === 'generate_image' ? 'images/generated.png' : '.'),
    );
    this.emitOp({
      conversationId: input.conversationId,
      messageId: input.messageId,
      op: input.name as WorkspaceOpEvent['op'],
      path: relPath,
      status: 'running',
    });

    const capturesCheckpoint = input.name === 'write_file' || input.name === 'delete_file';
    const previousContent = capturesCheckpoint
      ? this.workspace.tryReadFile(input.workspacePath, relPath)
      : null;

    try {
      let result: string;
      if (input.name === 'generate_image') {
        result = await this.images.generateForTool({
          prompt: String(args.prompt ?? ''),
          workspaceRoot: input.workspacePath,
          relativePath: relPath,
          size: args.size ? String(args.size) : undefined,
          conversationId: input.conversationId,
          serverId: input.serverId,
        });
      } else {
        result = this.workspace.executeTool(input.workspacePath, input.name, args);
      }

      const checkpointId = capturesCheckpoint
        ? this.store.addCheckpoint({
            conversationId: input.conversationId,
            relativePath: relPath,
            previousContent,
            previousExisted: previousContent !== null,
          }).id
        : undefined;

      this.emitOp({
        conversationId: input.conversationId,
        messageId: input.messageId,
        op: input.name as WorkspaceOpEvent['op'],
        path: relPath,
        status: 'ok',
        detail: result.slice(0, 240),
        checkpointId,
      });
      return result;
    } catch (error) {
      const message =
        error instanceof WorkspaceError || error instanceof Error ? error.message : String(error);
      this.emitOp({
        conversationId: input.conversationId,
        messageId: input.messageId,
        op: input.name as WorkspaceOpEvent['op'],
        path: relPath,
        status: 'error',
        detail: message,
      });
      return `Error: ${message}`;
    }
  }

  private emitToken(conversationId: string, messageId: string, delta: string): void {
    const tokenEvent: ChatTokenEvent = { conversationId, messageId, delta };
    this.getWindow()?.webContents.send('chat:token', tokenEvent);
  }

  private emitOp(event: WorkspaceOpEvent): void {
    this.getWindow()?.webContents.send('workspace:op', event);
  }

  cancel(conversationId: string): boolean {
    const orchCancelled = this.orchestrator.cancel(conversationId);
    const controller = this.abortControllers.get(conversationId);
    if (!controller) return orchCancelled;
    controller.abort();
    this.abortControllers.delete(conversationId);
    return true;
  }

  getActiveMessageId(): string | null {
    return this.activeMessageId ?? this.orchestrator.getActiveMessageId();
  }
}
