import { create } from 'zustand';
import type {
  ChatMessage,
  Conversation,
  ConversationKind,
  FolderEntry,
  OrchestratorStepEvent,
  WorkspaceOpEvent,
} from '../../shared/types';

type ChatState = {
  conversations: Conversation[];
  folders: FolderEntry[];
  activeConversationId: string | null;
  messages: ChatMessage[];
  activePersonaId: string;
  streamingMessageId: string | null;
  streamingContent: string;
  streamingPersonaId: string | null;
  isStreaming: boolean;
  error: string | null;
  newChatOpen: boolean;
  newChatKind: ConversationKind;
  newChatFolder: string | null;
  workspaceOps: WorkspaceOpEvent[];
  generatingImage: boolean;
  modelStatus: string | null;
  orchestratorStatus: string | null;
  bootstrap: () => Promise<void>;
  loadFolders: () => Promise<void>;
  openFolder: () => Promise<void>;
  selectConversation: (id: string) => Promise<void>;
  createConversation: (
    workspacePath?: string | null,
    kind?: ConversationKind,
    title?: string | null,
  ) => Promise<void>;
  deleteConversation: (id: string) => Promise<void>;
  setActivePersona: (personaId: string) => void;
  setNewChatOpen: (open: boolean, kind?: ConversationKind, folderPath?: string | null) => void;
  sendMessage: (content: string) => Promise<void>;
  generateImage: (input: {
    prompt: string;
    size: string;
    steps?: number;
    cfgScale?: number;
    seed?: number;
    saveToWorkspacePath?: string | null;
  }) => Promise<void>;
  cancelStream: () => Promise<void>;
  appendToken: (messageId: string, delta: string) => void;
  appendWorkspaceOp: (op: WorkspaceOpEvent) => void;
  setModelStatus: (message: string | null) => void;
  applyOrchestratorStep: (event: OrchestratorStepEvent) => void;
  reloadMessages: (conversationId: string) => Promise<void>;
  completeStream: (messageId: string, content: string, personaId: string) => Promise<void>;
  failStream: (message: string) => void;
};

export const useChatStore = create<ChatState>((set, get) => ({
  conversations: [],
  folders: [],
  activeConversationId: null,
  messages: [],
  activePersonaId: 'general',
  streamingMessageId: null,
  streamingContent: '',
  streamingPersonaId: null,
  isStreaming: false,
  error: null,
  newChatOpen: false,
  newChatKind: 'chat',
  newChatFolder: null,
  workspaceOps: [],
  generatingImage: false,
  modelStatus: null,
  orchestratorStatus: null,

  bootstrap: async () => {
    let conversations = await window.api.listConversations();
    if (!conversations.length) {
      const created = await window.api.createConversation({ kind: 'chat' });
      conversations = [created];
    }
    const folders = await window.api.listFolders();
    const activeId = conversations[0].id;
    const messages = await window.api.listMessages(activeId);
    set({
      conversations,
      folders,
      activeConversationId: activeId,
      messages,
      error: null,
      workspaceOps: [],
      orchestratorStatus: null,
    });
  },

  loadFolders: async () => {
    const folders = await window.api.listFolders();
    set({ folders });
  },

  openFolder: async () => {
    const folder = await window.api.openFolder();
    if (!folder) return;
    set((state) => ({
      folders: state.folders.some((item) => item.path === folder.path)
        ? state.folders
        : [...state.folders, folder],
    }));
  },

  selectConversation: async (id) => {
    const messages = await window.api.listMessages(id);
    set({
      activeConversationId: id,
      messages,
      error: null,
      streamingMessageId: null,
      streamingContent: '',
      streamingPersonaId: null,
      isStreaming: false,
      workspaceOps: [],
      generatingImage: false,
      orchestratorStatus: null,
    });
  },

  createConversation: async (workspacePath = null, kind = 'chat', title = null) => {
    const trimmed = title?.trim();
    const created = await window.api.createConversation({
      workspacePath,
      kind,
      ...(trimmed ? { title: trimmed } : {}),
    });
    const conversations = await window.api.listConversations();
    set({
      conversations,
      activeConversationId: created.id,
      messages: [],
      error: null,
      newChatOpen: false,
      workspaceOps: [],
      generatingImage: false,
      orchestratorStatus: null,
    });
  },

  deleteConversation: async (id) => {
    await window.api.deleteConversation(id);
    let conversations = await window.api.listConversations();
    if (!conversations.length) {
      const created = await window.api.createConversation({ kind: 'chat' });
      conversations = [created];
    }
    const nextId =
      get().activeConversationId === id ? conversations[0].id : get().activeConversationId;
    const messages = nextId ? await window.api.listMessages(nextId) : [];
    set({
      conversations,
      activeConversationId: nextId,
      messages,
      workspaceOps: [],
      orchestratorStatus: null,
    });
  },

  setActivePersona: (personaId) => set({ activePersonaId: personaId }),
  setNewChatOpen: (open, kind = 'chat', folderPath = null) =>
    set({ newChatOpen: open, newChatKind: kind, newChatFolder: open ? folderPath : null }),

  sendMessage: async (content) => {
    const trimmed = content.trim();
    const conversationId = get().activeConversationId;
    if (!trimmed || !conversationId || get().isStreaming || get().generatingImage) return;

    const conversation = get().conversations.find((item) => item.id === conversationId);
    const isOrchestrator = conversation?.kind === 'orchestrator';
    const personaId = isOrchestrator ? 'orchestrator' : get().activePersonaId;

    const userMessage: ChatMessage = {
      id: `temp-user-${Date.now()}`,
      conversationId,
      role: 'user',
      content: trimmed,
      personaId: null,
      createdAt: Date.now(),
    };

    const assistantId = `temp-assistant-${Date.now()}`;

    set((state) => ({
      messages: [...state.messages, userMessage],
      isStreaming: true,
      streamingMessageId: assistantId,
      streamingContent: '',
      streamingPersonaId: personaId,
      error: null,
      modelStatus: null,
      orchestratorStatus: isOrchestrator ? 'Starting orchestrator…' : null,
      workspaceOps: [],
    }));

    try {
      const result = await window.api.sendChat({
        conversationId,
        content: trimmed,
        personaId,
      });

      set((state) => ({
        streamingMessageId: result.assistantMessageId,
        messages: state.messages.map((message) =>
          message.id === userMessage.id ? { ...message, id: result.userMessageId } : message,
        ),
      }));
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to send message';
      set({
        isStreaming: false,
        streamingMessageId: null,
        streamingContent: '',
        streamingPersonaId: null,
        orchestratorStatus: null,
        error: message,
      });
    }
  },

  generateImage: async ({ prompt, size, steps, cfgScale, seed, saveToWorkspacePath }) => {
    const conversationId = get().activeConversationId;
    const trimmed = prompt.trim();
    if (!conversationId || !trimmed || get().generatingImage || get().isStreaming) return;

    set({ generatingImage: true, error: null, modelStatus: null });
    try {
      const settings = await window.api.getSettings();
      await window.api.generateImage({
        conversationId,
        prompt: trimmed,
        model: settings.imageModel || undefined,
        size,
        steps,
        cfgScale,
        seed,
        saveToWorkspacePath: saveToWorkspacePath ?? null,
      });
      const conversations = await window.api.listConversations();
      const messages = await window.api.listMessages(conversationId);
      set({
        conversations,
        messages,
        generatingImage: false,
        modelStatus: null,
      });
    } catch (error) {
      set({
        generatingImage: false,
        modelStatus: null,
        error: error instanceof Error ? error.message : 'Image generation failed',
      });
    }
  },

  cancelStream: async () => {
    await window.api.cancelChat();
  },

  appendToken: (messageId, delta) => {
    set((state) => {
      if (state.streamingMessageId && state.streamingMessageId !== messageId) {
        return {
          streamingMessageId: messageId,
          streamingContent: state.streamingContent + delta,
        };
      }
      return {
        streamingMessageId: messageId,
        streamingContent: state.streamingContent + delta,
      };
    });
  },

  appendWorkspaceOp: (op) => {
    set((state) => {
      const withoutSameRunning = state.workspaceOps.filter(
        (item) =>
          !(
            item.messageId === op.messageId &&
            item.op === op.op &&
            item.path === op.path &&
            item.status === 'running'
          ),
      );
      return { workspaceOps: [...withoutSameRunning, op].slice(-20) };
    });
  },

  setModelStatus: (message) => set({ modelStatus: message }),

  applyOrchestratorStep: (event) => {
    if (event.conversationId !== get().activeConversationId) return;
    set({
      orchestratorStatus: event.phase === 'done' ? null : event.label,
      streamingPersonaId: event.personaId,
      streamingContent: event.phase === 'synthesizing' ? get().streamingContent : '',
    });
  },

  reloadMessages: async (conversationId) => {
    if (conversationId !== get().activeConversationId) return;
    const messages = await window.api.listMessages(conversationId);
    const conversations = await window.api.listConversations();
    set({ messages, conversations });
  },

  completeStream: async (messageId, content, personaId) => {
    const conversationId = get().activeConversationId;
    const conversations = await window.api.listConversations();
    const messages = conversationId
      ? await window.api.listMessages(conversationId)
      : get().messages;

    const hasMessage = messages.some((message) => message.id === messageId);
    const nextMessages = hasMessage
      ? messages
      : conversationId
        ? [
            ...messages,
            {
              id: messageId,
              conversationId,
              role: 'assistant' as const,
              content,
              personaId,
              createdAt: Date.now(),
            },
          ]
        : messages;

    set({
      conversations,
      messages: nextMessages,
      isStreaming: false,
      streamingMessageId: null,
      streamingContent: '',
      streamingPersonaId: null,
      modelStatus: null,
      orchestratorStatus: null,
    });
  },

  failStream: (message) => {
    set({
      isStreaming: false,
      streamingMessageId: null,
      streamingContent: '',
      streamingPersonaId: null,
      modelStatus: null,
      orchestratorStatus: null,
      error: message,
    });
  },
}));
