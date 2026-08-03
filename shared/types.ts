export type MessageRole = 'user' | 'assistant' | 'system';

export type Persona = {
  id: string;
  name: string;
  description: string;
  systemPrompt: string;
  defaultModel?: string;
  color: string;
};

export type ChatMessage = {
  id: string;
  conversationId: string;
  role: MessageRole;
  content: string;
  personaId: string | null;
  createdAt: number;
};

export type ConversationKind = 'chat' | 'image' | 'orchestrator';

export type Conversation = {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  workspacePath: string | null;
  kind: ConversationKind;
  model: string | null;
  serverId: string | null;
};

export type CreateConversationRequest = {
  title?: string;
  workspacePath?: string | null;
  kind?: ConversationKind;
};

export type FolderEntry = {
  path: string;
  addedAt: number;
};

export type SearchResult = {
  conversationId: string;
  title: string;
  kind: ConversationKind;
  workspacePath: string | null;
  matchedInTitle: boolean;
  snippet: string | null;
};

export type ThemeMode = 'light' | 'dark' | 'terminal';

export type ProviderType = 'openai' | 'lemonade' | 'ollama';

export type ServerProfile = {
  id: string;
  name: string;
  providerType: ProviderType;
  baseUrl: string;
  apiKey: string;
  maxHistory: number;
};

export type AppSettings = {
  baseUrl: string;
  apiKey: string;
  model: string;
  imageModel: string;
  maxHistory: number;
  theme: ThemeMode;
  providerType: ProviderType;
  servers: ServerProfile[];
  activeServerId: string | null;
};

export type HealthStatus = {
  ok: boolean;
  message: string;
  latencyMs?: number;
};

export type ModelInfo = {
  id: string;
  ownedBy?: string;
};

export type LoadedModelsResult = {
  names: string[];
  /** False when the server doesn't expose any load-status signal at all, so an empty `names` here means "unknown," not "definitely not loaded." */
  supported: boolean;
};

export type ChatSendRequest = {
  conversationId: string;
  content: string;
  personaId: string;
};

export type ChatTokenEvent = {
  conversationId: string;
  messageId: string;
  delta: string;
};

export type ChatDoneEvent = {
  conversationId: string;
  messageId: string;
  content: string;
  personaId: string;
};

export type ChatErrorEvent = {
  conversationId: string;
  messageId: string;
  code:
    | 'server_unreachable'
    | 'model_not_loaded'
    | 'context_exceeded'
    | 'unsupported'
    | 'cancelled'
    | 'unknown';
  message: string;
};

export type WorkspaceOpEvent = {
  conversationId: string;
  messageId: string;
  op: 'list_dir' | 'read_file' | 'write_file' | 'delete_file' | 'generate_image';
  path: string;
  status: 'running' | 'ok' | 'error';
  detail?: string;
  /** Present on successful write_file/delete_file ops; lets the UI offer a diff/revert for that change. */
  checkpointId?: string;
};

export type FileCheckpoint = {
  id: string;
  conversationId: string;
  relativePath: string;
  /** File content immediately before the write/delete, or null if the file didn't exist yet (the op created it). */
  previousContent: string | null;
  previousExisted: boolean;
  createdAt: number;
};

export type CheckpointDiff = {
  path: string;
  before: string | null;
  after: string | null;
};

export type GenerateImageRequest = {
  conversationId: string;
  prompt: string;
  model?: string;
  size?: string;
  steps?: number;
  cfgScale?: number;
  seed?: number;
  mode?: 'generate' | 'edit' | 'variations';
  upscale?: string;
  saveToWorkspacePath?: string | null;
};

export type GeneratedImageInfo = {
  id: string;
  conversationId: string;
  prompt: string;
  model: string;
  size: string;
  fileName: string;
  workspaceRelativePath: string | null;
  createdAt: number;
};

export type ImageMessagePayload = {
  id: string;
  prompt: string;
  model: string;
  size: string;
  fileName: string;
  workspaceRelativePath: string | null;
};

export type ModelStatusEvent = {
  model: string;
  phase: 'checking' | 'loading' | 'ready' | 'error';
  message: string;
};

export type OrchestratorStepEvent = {
  conversationId: string;
  phase: 'planning' | 'specialist' | 'synthesizing' | 'done';
  personaId: string | null;
  label: string;
};

export type ChatMessagesUpdatedEvent = {
  conversationId: string;
};

export type AppErrorCode = ChatErrorEvent['code'];

export function isLikelyImageModel(modelId: string): boolean {
  const id = modelId.toLowerCase();
  return (
    id.includes('sd') ||
    id.includes('stable') ||
    id.includes('diffusion') ||
    id.includes('flux') ||
    id.includes('image') ||
    (id.includes('turbo') && (id.includes('sd') || id.includes('xl')))
  );
}

export function encodeImageMessage(payload: ImageMessagePayload): string {
  return `Generated image\n[[MA_IMAGE]]\n${JSON.stringify(payload)}\n[[/MA_IMAGE]]`;
}

export function parseImageMessage(content: string): ImageMessagePayload | null {
  const match = content.match(/\[\[MA_IMAGE\]\]\s*([\s\S]*?)\s*\[\[\/MA_IMAGE\]\]/);
  if (!match) return null;
  try {
    return JSON.parse(match[1]) as ImageMessagePayload;
  } catch {
    return null;
  }
}
