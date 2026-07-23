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
  ChatMessagesUpdatedEvent,
  ChatSendRequest,
  ChatTokenEvent,
  OrchestratorStepEvent,
  Persona,
  WorkspaceOpEvent,
} from '../../shared/types';
import { LemonadeClient, LemonadeError } from './lemonadeClient';
import { ConversationStore } from './conversationStore';
import { PersonaRegistry } from './personaRegistry';
import { emitModelStatus } from './modelStatus';
import { parsePlan, type PlanResult } from './planParser';
import { WorkspaceError, WorkspaceService, workspaceTools } from './workspaceService';

const SPECIALIST_IDS = ['researcher', 'coder', 'critic'] as const;
const MAX_SPECIALIST_TOOL_ROUNDS = 4;

// Specialists get read-only workspace access: they can look around and
// consult files, but only a workspace chat can write/delete/generate.
const READ_ONLY_TOOL_NAMES = new Set(['list_dir', 'read_file']);
const readOnlyWorkspaceTools = workspaceTools.filter((tool) =>
  READ_ONLY_TOOL_NAMES.has(tool.function.name),
) as ChatCompletionTool[];

export class OrchestratorService {
  private abortController: AbortController | null = null;
  private activeMessageId: string | null = null;
  private workspace = new WorkspaceService();

  constructor(
    private lemonade: LemonadeClient,
    private store: ConversationStore,
    private personas: PersonaRegistry,
    private getSettings: () => AppSettings,
    private getWindow: () => BrowserWindow | null,
  ) {}

  async send(
    request: ChatSendRequest,
  ): Promise<{ userMessageId: string; assistantMessageId: string }> {
    if (this.abortController) {
      this.abortController.abort();
    }
    this.abortController = null;

    const conversation = this.store.getConversation(request.conversationId);
    if (!conversation) {
      throw new LemonadeError('unknown', 'Conversation not found');
    }

    const settings = this.getSettings();
    const orchestrator =
      this.personas.get('orchestrator') ?? this.personas.get('general') ?? this.personas.list()[0];
    const model = orchestrator.defaultModel || conversation.model || settings.model;

    if (!model) {
      throw new LemonadeError(
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
    const myController = new AbortController();
    this.abortController = myController;
    const signal = myController.signal;

    let fullContent = '';

    try {
      await this.lemonade.ensureModelLoaded(model, {
        signal,
        onStatus: (message) => emitModelStatus(this.getWindow, model, message),
      });

      const workspacePath = conversation.workspacePath;
      let workspaceTree: string | null = null;
      if (workspacePath) {
        try {
          workspaceTree = this.workspace.buildTree(workspacePath);
        } catch (error) {
          workspaceTree = error instanceof Error ? error.message : String(error);
        }
      }

      this.emitStep({
        conversationId: request.conversationId,
        phase: 'planning',
        personaId: orchestrator.id,
        label: 'Planning which specialists to use…',
      });

      const history = this.store.getMessages(request.conversationId);
      const capped = history.slice(-Math.max(1, settings.maxHistory));
      const priorContext = capped
        .filter((message) => message.role === 'user' || message.role === 'assistant')
        .map((message) => {
          const who =
            message.role === 'user'
              ? 'User'
              : message.personaId
                ? `Assistant(${message.personaId})`
                : 'Assistant';
          return `${who}: ${message.content}`;
        })
        .join('\n\n');

      const plan = await this.planSpecialists({
        orchestrator,
        model,
        userContent: request.content,
        priorContext,
        workspacePath,
        workspaceTree,
        signal,
      });

      const specialistNotes: Array<{ persona: Persona; content: string }> = [];

      for (const specialistId of plan.specialists) {
        if (signal.aborted) {
          throw new LemonadeError('cancelled', 'Generation cancelled');
        }

        const persona = this.personas.get(specialistId);
        if (!persona) continue;

        this.emitStep({
          conversationId: request.conversationId,
          phase: 'specialist',
          personaId: persona.id,
          label: `${persona.name} is working…`,
        });

        const specialistContent = await this.runSpecialist({
          persona,
          model,
          userContent: request.content,
          priorContext,
          planRationale: plan.rationale,
          workspacePath,
          workspaceTree,
          conversationId: request.conversationId,
          messageId: assistantMessageId,
          signal,
        });

        this.store.addMessage({
          conversationId: request.conversationId,
          role: 'assistant',
          content: specialistContent,
          personaId: persona.id,
        });
        this.emitMessagesUpdated(request.conversationId);
        specialistNotes.push({ persona, content: specialistContent });
      }

      this.emitStep({
        conversationId: request.conversationId,
        phase: 'synthesizing',
        personaId: orchestrator.id,
        label: 'Synthesizing final answer…',
      });

      fullContent = await this.synthesize({
        orchestrator,
        model,
        userContent: request.content,
        planRationale: plan.rationale,
        specialistNotes,
        workspacePath,
        workspaceTree,
        conversationId: request.conversationId,
        messageId: assistantMessageId,
        signal,
      });

      this.store.addMessage({
        id: assistantMessageId,
        conversationId: request.conversationId,
        role: 'assistant',
        content: fullContent,
        personaId: orchestrator.id,
      });

      this.emitStep({
        conversationId: request.conversationId,
        phase: 'done',
        personaId: orchestrator.id,
        label: 'Done',
      });

      const doneEvent: ChatDoneEvent = {
        conversationId: request.conversationId,
        messageId: assistantMessageId,
        content: fullContent,
        personaId: orchestrator.id,
      };
      this.getWindow()?.webContents.send('chat:done', doneEvent);

      return { userMessageId: userMessage.id, assistantMessageId };
    } catch (error) {
      const mapped =
        error instanceof LemonadeError
          ? error
          : new LemonadeError('unknown', error instanceof Error ? error.message : String(error));

      if (mapped.code !== 'cancelled' && fullContent) {
        this.store.addMessage({
          id: assistantMessageId,
          conversationId: request.conversationId,
          role: 'assistant',
          content: fullContent,
          personaId: orchestrator.id,
        });
      }

      const errorEvent: ChatErrorEvent = {
        conversationId: request.conversationId,
        messageId: assistantMessageId,
        code: mapped.code,
        message: mapped.message,
      };
      this.getWindow()?.webContents.send('chat:error', errorEvent);

      if (mapped.code !== 'cancelled') {
        throw mapped;
      }

      return { userMessageId: userMessage.id, assistantMessageId };
    } finally {
      if (this.abortController === myController) {
        this.abortController = null;
      }
      if (this.activeMessageId === assistantMessageId) {
        this.activeMessageId = null;
      }
    }
  }

  private async planSpecialists(input: {
    orchestrator: Persona;
    model: string;
    userContent: string;
    priorContext: string;
    workspacePath: string | null;
    workspaceTree: string | null;
    signal: AbortSignal;
  }): Promise<PlanResult> {
    const available = SPECIALIST_IDS.filter((id) => this.personas.get(id)).join(', ');
    const messages: ChatCompletionMessageParam[] = [
      {
        role: 'system',
        content: [
          input.orchestrator.systemPrompt,
          '',
          'Your job now is ONLY to choose which specialists should help with the user request.',
          `Available specialist ids: ${available}`,
          'Respond with JSON only, no markdown:',
          '{"specialists":["researcher"],"rationale":"why these were chosen"}',
          'Rules:',
          '- specialists must be a subset of the available ids',
          '- pick 1–3 specialists; omit any that are not useful',
          '- if the request is simple, pick one specialist or an empty array',
          '- do not answer the user yet',
          ...(input.workspaceTree
            ? [
                '',
                `A read-only workspace folder is bound to this conversation: ${input.workspacePath}`,
                'Specialists you pick will be able to list and read files in it.',
                'Workspace tree:',
                input.workspaceTree,
              ]
            : []),
        ].join('\n'),
      },
      {
        role: 'user',
        content: [
          input.priorContext ? `Conversation so far:\n${input.priorContext}\n` : '',
          `Latest user request:\n${input.userContent}`,
        ]
          .filter(Boolean)
          .join('\n'),
      },
    ];

    const completion = await this.lemonade.completeChat(messages, input.model, {
      signal: input.signal,
    });
    return parsePlan(completion.content || '', SPECIALIST_IDS);
  }

  private async runSpecialist(input: {
    persona: Persona;
    model: string;
    userContent: string;
    priorContext: string;
    planRationale: string;
    workspacePath: string | null;
    workspaceTree: string | null;
    conversationId: string;
    messageId: string;
    signal: AbortSignal;
  }): Promise<string> {
    const specialistModel = input.persona.defaultModel || input.model;
    const systemParts = [
      input.persona.systemPrompt,
      '',
      'You are contributing as a specialist inside a multi-agent workflow.',
      'Focus on your specialty. Do not pretend to be the final answer for the user.',
      'Be concrete and useful; the orchestrator will synthesize your notes.',
      `Orchestrator rationale for involving you: ${input.planRationale}`,
    ];
    if (input.workspacePath && input.workspaceTree) {
      systemParts.push(
        '',
        `You have read-only access to a workspace folder bound to this conversation: ${input.workspacePath}`,
        'Use the list_dir and read_file tools to inspect actual files before answering — do not guess at contents from the tree alone.',
        'Workspace tree:',
        input.workspaceTree,
      );
    }

    const messages: ChatCompletionMessageParam[] = [
      { role: 'system', content: systemParts.join('\n') },
      {
        role: 'user',
        content: [
          input.priorContext ? `Conversation so far:\n${input.priorContext}\n` : '',
          `User request:\n${input.userContent}`,
        ]
          .filter(Boolean)
          .join('\n'),
      },
    ];

    await this.lemonade.ensureModelLoaded(specialistModel, {
      signal: input.signal,
      onStatus: (message) => emitModelStatus(this.getWindow, specialistModel, message),
    });

    if (!input.workspacePath) {
      const completion = await this.lemonade.completeChat(messages, specialistModel, {
        signal: input.signal,
      });
      const content = (completion.content || '').trim();
      return content || `(${input.persona.name} returned an empty response.)`;
    }

    return this.runSpecialistWithTools({
      messages,
      model: specialistModel,
      workspacePath: input.workspacePath,
      persona: input.persona,
      conversationId: input.conversationId,
      messageId: input.messageId,
      signal: input.signal,
    });
  }

  private async runSpecialistWithTools(input: {
    messages: ChatCompletionMessageParam[];
    model: string;
    workspacePath: string;
    persona: Persona;
    conversationId: string;
    messageId: string;
    signal: AbortSignal;
  }): Promise<string> {
    const messages = [...input.messages];
    let toolsEnabled = true;

    for (let round = 0; round < MAX_SPECIALIST_TOOL_ROUNDS; round += 1) {
      if (input.signal.aborted) {
        throw new LemonadeError('cancelled', 'Generation cancelled');
      }

      let completion;
      try {
        completion = await this.lemonade.completeChat(messages, input.model, {
          tools: toolsEnabled ? readOnlyWorkspaceTools : undefined,
          signal: input.signal,
        });
      } catch (error) {
        if (toolsEnabled) {
          toolsEnabled = false;
          completion = await this.lemonade.completeChat(messages, input.model, {
            signal: input.signal,
          });
        } else {
          throw error;
        }
      }

      if (!completion.toolCalls.length) {
        const content = (completion.content || '').trim();
        return content || `(${input.persona.name} returned an empty response.)`;
      }

      messages.push({
        role: 'assistant',
        content: completion.content || null,
        tool_calls: completion.toolCalls.map((call) => ({
          id: call.id,
          type: 'function' as const,
          function: { name: call.name, arguments: call.arguments },
        })),
      });

      for (const call of completion.toolCalls) {
        const result = this.executeSpecialistTool({
          workspacePath: input.workspacePath,
          conversationId: input.conversationId,
          messageId: input.messageId,
          name: call.name,
          rawArgs: call.arguments,
        });
        messages.push({
          role: 'tool',
          tool_call_id: call.id,
          content: result,
        });
      }
    }

    return `(${input.persona.name} stopped after exploring the workspace — try a more specific question.)`;
  }

  private executeSpecialistTool(input: {
    workspacePath: string;
    conversationId: string;
    messageId: string;
    name: string;
    rawArgs: string;
  }): string {
    let args: Record<string, unknown>;
    try {
      args = JSON.parse(input.rawArgs || '{}') as Record<string, unknown>;
    } catch {
      args = {};
    }

    const relPath = String(args.path ?? '.');
    this.emitOp({
      conversationId: input.conversationId,
      messageId: input.messageId,
      op: input.name as WorkspaceOpEvent['op'],
      path: relPath,
      status: 'running',
    });

    if (!READ_ONLY_TOOL_NAMES.has(input.name)) {
      const message = `Tool "${input.name}" is not available to specialists (read-only access).`;
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

    try {
      const result = this.workspace.executeTool(input.workspacePath, input.name, args);
      this.emitOp({
        conversationId: input.conversationId,
        messageId: input.messageId,
        op: input.name as WorkspaceOpEvent['op'],
        path: relPath,
        status: 'ok',
        detail: result.slice(0, 240),
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

  private async synthesize(input: {
    orchestrator: Persona;
    model: string;
    userContent: string;
    planRationale: string;
    specialistNotes: Array<{ persona: Persona; content: string }>;
    workspacePath: string | null;
    workspaceTree: string | null;
    conversationId: string;
    messageId: string;
    signal: AbortSignal;
  }): Promise<string> {
    const notesBlock =
      input.specialistNotes.length > 0
        ? input.specialistNotes
            .map((note) => `### ${note.persona.name}\n${note.content}`)
            .join('\n\n')
        : '(No specialists were consulted.)';

    const messages: ChatCompletionMessageParam[] = [
      {
        role: 'system',
        content: [
          input.orchestrator.systemPrompt,
          '',
          'Synthesize a final answer for the user from the specialist notes.',
          'Lead with the answer, resolve disagreements, and keep it clear.',
          'Do not mention internal planning JSON or that you are an orchestrator unless useful.',
          `Plan rationale: ${input.planRationale}`,
          ...(input.workspaceTree
            ? [
                '',
                `Workspace folder bound to this conversation: ${input.workspacePath}`,
                'Workspace tree (for reference; specialist notes already reflect its actual contents):',
                input.workspaceTree,
              ]
            : []),
        ].join('\n'),
      },
      {
        role: 'user',
        content: [`User request:\n${input.userContent}`, '', 'Specialist notes:', notesBlock].join(
          '\n',
        ),
      },
    ];

    let full = '';
    for await (const delta of this.lemonade.streamChat(messages, input.model, input.signal)) {
      full += delta;
      this.emitToken(input.conversationId, input.messageId, delta);
    }
    return full.trim() || 'I could not produce a final answer from the specialist notes.';
  }

  private emitToken(conversationId: string, messageId: string, delta: string): void {
    const tokenEvent: ChatTokenEvent = { conversationId, messageId, delta };
    this.getWindow()?.webContents.send('chat:token', tokenEvent);
  }

  private emitOp(event: WorkspaceOpEvent): void {
    this.getWindow()?.webContents.send('workspace:op', event);
  }

  private emitStep(event: OrchestratorStepEvent): void {
    this.getWindow()?.webContents.send('orchestrator:step', event);
  }

  private emitMessagesUpdated(conversationId: string): void {
    const event: ChatMessagesUpdatedEvent = { conversationId };
    this.getWindow()?.webContents.send('chat:messagesUpdated', event);
  }

  cancel(): boolean {
    if (!this.abortController) return false;
    this.abortController.abort();
    this.abortController = null;
    return true;
  }

  getActiveMessageId(): string | null {
    return this.activeMessageId;
  }
}
