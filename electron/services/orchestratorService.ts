import { randomUUID } from 'node:crypto';
import type { BrowserWindow } from 'electron';
import type { ChatCompletionMessageParam } from 'openai/resources/chat/completions';
import type {
  AppSettings,
  ChatDoneEvent,
  ChatErrorEvent,
  ChatMessagesUpdatedEvent,
  ChatSendRequest,
  ChatTokenEvent,
  ModelStatusEvent,
  OrchestratorStepEvent,
  Persona,
} from '../../shared/types';
import { LemonadeClient, LemonadeError } from './lemonadeClient';
import { ConversationStore } from './conversationStore';
import { PersonaRegistry } from './personaRegistry';

const SPECIALIST_IDS = ['researcher', 'coder', 'critic'] as const;

type PlanResult = {
  specialists: string[];
  rationale: string;
};

export class OrchestratorService {
  private abortController: AbortController | null = null;
  private activeMessageId: string | null = null;

  constructor(
    private lemonade: LemonadeClient,
    private store: ConversationStore,
    private personas: PersonaRegistry,
    private getSettings: () => AppSettings,
    private getWindow: () => BrowserWindow | null,
  ) {}

  async send(request: ChatSendRequest): Promise<{ userMessageId: string; assistantMessageId: string }> {
    if (this.abortController) {
      this.abortController.abort();
    }
    this.abortController = null;

    const settings = this.getSettings();
    const orchestrator =
      this.personas.get('orchestrator') ?? this.personas.get('general') ?? this.personas.list()[0];
    const model = orchestrator.defaultModel || settings.model;

    if (!model) {
      throw new LemonadeError(
        'model_not_loaded',
        'No model selected. Choose a model in the top bar or Settings.',
      );
    }

    const conversation = this.store.getConversation(request.conversationId);
    if (!conversation) {
      throw new LemonadeError('unknown', 'Conversation not found');
    }

    const userMessage = this.store.addMessage({
      conversationId: request.conversationId,
      role: 'user',
      content: request.content,
    });

    const assistantMessageId = randomUUID();
    this.activeMessageId = assistantMessageId;
    this.abortController = new AbortController();
    const signal = this.abortController.signal;

    let fullContent = '';

    try {
      await this.lemonade.ensureModelLoaded(model, {
        signal,
        onStatus: (message) => this.emitModelStatus(model, message),
      });

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
      this.abortController = null;
      this.activeMessageId = null;
    }
  }

  private async planSpecialists(input: {
    orchestrator: Persona;
    model: string;
    userContent: string;
    priorContext: string;
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
    return this.parsePlan(completion.content || '');
  }

  private parsePlan(raw: string): PlanResult {
    const fallback: PlanResult = {
      specialists: ['researcher'],
      rationale: 'Default plan',
    };

    const jsonMatch = raw.match(/\{[\s\S]*\}/);
    if (!jsonMatch) return fallback;

    try {
      const parsed = JSON.parse(jsonMatch[0]) as {
        specialists?: unknown;
        rationale?: unknown;
      };
      const ids = Array.isArray(parsed.specialists)
        ? parsed.specialists
            .map((item) => String(item))
            .filter((id): id is (typeof SPECIALIST_IDS)[number] =>
              (SPECIALIST_IDS as readonly string[]).includes(id),
            )
        : [];
      const unique = [...new Set(ids)].slice(0, 3);
      return {
        specialists: unique,
        rationale:
          typeof parsed.rationale === 'string' && parsed.rationale.trim()
            ? parsed.rationale.trim()
            : 'Plan selected by orchestrator',
      };
    } catch {
      return fallback;
    }
  }

  private async runSpecialist(input: {
    persona: Persona;
    model: string;
    userContent: string;
    priorContext: string;
    planRationale: string;
    signal: AbortSignal;
  }): Promise<string> {
    const specialistModel = input.persona.defaultModel || input.model;
    const messages: ChatCompletionMessageParam[] = [
      {
        role: 'system',
        content: [
          input.persona.systemPrompt,
          '',
          'You are contributing as a specialist inside a multi-agent workflow.',
          'Focus on your specialty. Do not pretend to be the final answer for the user.',
          'Be concrete and useful; the orchestrator will synthesize your notes.',
          `Orchestrator rationale for involving you: ${input.planRationale}`,
        ].join('\n'),
      },
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
      onStatus: (message) => this.emitModelStatus(specialistModel, message),
    });

    const completion = await this.lemonade.completeChat(messages, specialistModel, {
      signal: input.signal,
    });
    const content = (completion.content || '').trim();
    return content || `(${input.persona.name} returned an empty response.)`;
  }

  private async synthesize(input: {
    orchestrator: Persona;
    model: string;
    userContent: string;
    planRationale: string;
    specialistNotes: Array<{ persona: Persona; content: string }>;
    conversationId: string;
    messageId: string;
    signal: AbortSignal;
  }): Promise<string> {
    const notesBlock =
      input.specialistNotes.length > 0
        ? input.specialistNotes
            .map(
              (note) =>
                `### ${note.persona.name}\n${note.content}`,
            )
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
        ].join('\n'),
      },
      {
        role: 'user',
        content: [
          `User request:\n${input.userContent}`,
          '',
          'Specialist notes:',
          notesBlock,
        ].join('\n'),
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

  private emitStep(event: OrchestratorStepEvent): void {
    this.getWindow()?.webContents.send('orchestrator:step', event);
  }

  private emitMessagesUpdated(conversationId: string): void {
    const event: ChatMessagesUpdatedEvent = { conversationId };
    this.getWindow()?.webContents.send('chat:messagesUpdated', event);
  }

  private emitModelStatus(model: string, message: string): void {
    const lower = message.toLowerCase();
    const phase: ModelStatusEvent['phase'] = lower.includes('ready')
      ? 'ready'
      : lower.includes('loading') || lower.includes('waiting')
        ? 'loading'
        : 'checking';
    const event: ModelStatusEvent = { model, phase, message };
    this.getWindow()?.webContents.send('model:status', event);
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
