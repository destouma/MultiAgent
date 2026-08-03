import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron';
import type {
  AppSettings,
  CheckpointDiff,
  ChatDoneEvent,
  ChatErrorEvent,
  ChatMessagesUpdatedEvent,
  ChatSendRequest,
  ChatTokenEvent,
  Conversation,
  ChatMessage,
  CreateConversationRequest,
  FolderEntry,
  GenerateImageRequest,
  GeneratedImageInfo,
  HealthStatus,
  LoadedModelsResult,
  ModelInfo,
  ModelStatusEvent,
  OrchestratorStepEvent,
  Persona,
  SearchResult,
  WorkspaceOpEvent,
} from '../../shared/types';

const api = {
  getSettings: (): Promise<AppSettings> => ipcRenderer.invoke('settings:get'),
  setSettings: (partial: Partial<AppSettings>): Promise<AppSettings> =>
    ipcRenderer.invoke('settings:set', partial),

  listModels: (): Promise<ModelInfo[]> => ipcRenderer.invoke('models:list'),
  listLoadedModels: (): Promise<LoadedModelsResult> => ipcRenderer.invoke('models:loaded'),
  loadModel: (model: string): Promise<boolean> => ipcRenderer.invoke('models:load', model),
  listModelsForServer: (serverId: string | null): Promise<ModelInfo[]> =>
    ipcRenderer.invoke('models:listForServer', serverId),
  listLoadedModelsForServer: (serverId: string | null): Promise<LoadedModelsResult> =>
    ipcRenderer.invoke('models:loadedForServer', serverId),
  checkHealth: (): Promise<HealthStatus> => ipcRenderer.invoke('health:check'),
  checkHealthForServer: (serverId: string | null): Promise<HealthStatus> =>
    ipcRenderer.invoke('health:checkForServer', serverId),
  listPersonas: (): Promise<Persona[]> => ipcRenderer.invoke('personas:list'),

  sendChat: (
    request: ChatSendRequest,
  ): Promise<{ userMessageId: string; assistantMessageId: string }> =>
    ipcRenderer.invoke('chat:send', request),
  cancelChat: (conversationId: string): Promise<boolean> =>
    ipcRenderer.invoke('chat:cancel', conversationId),

  listConversations: (): Promise<Conversation[]> => ipcRenderer.invoke('conversations:list'),
  createConversation: (request?: CreateConversationRequest | string): Promise<Conversation> =>
    ipcRenderer.invoke('conversations:create', request),
  renameConversation: (id: string, title: string): Promise<Conversation | null> =>
    ipcRenderer.invoke('conversations:rename', id, title),
  setConversationModel: (id: string, model: string | null): Promise<Conversation | null> =>
    ipcRenderer.invoke('conversations:setModel', id, model),
  setConversationServer: (id: string, serverId: string | null): Promise<Conversation | null> =>
    ipcRenderer.invoke('conversations:setServer', id, serverId),
  exportConversation: (id: string, format: 'markdown' | 'json'): Promise<string | null> =>
    ipcRenderer.invoke('conversations:export', id, format),
  deleteConversation: (id: string): Promise<boolean> =>
    ipcRenderer.invoke('conversations:delete', id),
  getConversation: (id: string): Promise<Conversation | null> =>
    ipcRenderer.invoke('conversations:get', id),
  listMessages: (conversationId: string): Promise<ChatMessage[]> =>
    ipcRenderer.invoke('messages:list', conversationId),
  deleteMessagesFrom: (conversationId: string, messageId: string): Promise<boolean> =>
    ipcRenderer.invoke('messages:deleteFrom', conversationId, messageId),

  search: (term: string): Promise<SearchResult[]> => ipcRenderer.invoke('search:query', term),

  diffCheckpoint: (checkpointId: string): Promise<CheckpointDiff> =>
    ipcRenderer.invoke('checkpoints:diff', checkpointId),
  revertCheckpoint: (checkpointId: string): Promise<boolean> =>
    ipcRenderer.invoke('checkpoints:revert', checkpointId),

  listFolders: (): Promise<FolderEntry[]> => ipcRenderer.invoke('folders:list'),
  openFolder: (): Promise<FolderEntry | null> => ipcRenderer.invoke('folders:open'),
  removeFolder: (folderPath: string): Promise<boolean> =>
    ipcRenderer.invoke('folders:remove', folderPath),

  generateImage: (
    request: GenerateImageRequest,
  ): Promise<{ info: GeneratedImageInfo; message: ChatMessage }> =>
    ipcRenderer.invoke('images:generate', request),
  getImageDataUrl: (fileName: string): Promise<string> =>
    ipcRenderer.invoke('images:getDataUrl', fileName),
  downloadImage: (fileName: string, defaultName?: string): Promise<string | null> =>
    ipcRenderer.invoke('images:download', fileName, defaultName),
  saveImageToWorkspace: (
    conversationId: string,
    fileName: string,
    relativePath?: string,
  ): Promise<string> =>
    ipcRenderer.invoke('images:saveToWorkspace', conversationId, fileName, relativePath),

  onChatToken: (callback: (event: ChatTokenEvent) => void): (() => void) => {
    const listener = (_event: IpcRendererEvent, payload: ChatTokenEvent) => callback(payload);
    ipcRenderer.on('chat:token', listener);
    return () => ipcRenderer.removeListener('chat:token', listener);
  },
  onChatDone: (callback: (event: ChatDoneEvent) => void): (() => void) => {
    const listener = (_event: IpcRendererEvent, payload: ChatDoneEvent) => callback(payload);
    ipcRenderer.on('chat:done', listener);
    return () => ipcRenderer.removeListener('chat:done', listener);
  },
  onChatError: (callback: (event: ChatErrorEvent) => void): (() => void) => {
    const listener = (_event: IpcRendererEvent, payload: ChatErrorEvent) => callback(payload);
    ipcRenderer.on('chat:error', listener);
    return () => ipcRenderer.removeListener('chat:error', listener);
  },
  onChatMessagesUpdated: (callback: (event: ChatMessagesUpdatedEvent) => void): (() => void) => {
    const listener = (_event: IpcRendererEvent, payload: ChatMessagesUpdatedEvent) =>
      callback(payload);
    ipcRenderer.on('chat:messagesUpdated', listener);
    return () => ipcRenderer.removeListener('chat:messagesUpdated', listener);
  },
  onWorkspaceOp: (callback: (event: WorkspaceOpEvent) => void): (() => void) => {
    const listener = (_event: IpcRendererEvent, payload: WorkspaceOpEvent) => callback(payload);
    ipcRenderer.on('workspace:op', listener);
    return () => ipcRenderer.removeListener('workspace:op', listener);
  },
  onModelStatus: (callback: (event: ModelStatusEvent) => void): (() => void) => {
    const listener = (_event: IpcRendererEvent, payload: ModelStatusEvent) => callback(payload);
    ipcRenderer.on('model:status', listener);
    return () => ipcRenderer.removeListener('model:status', listener);
  },
  onOrchestratorStep: (callback: (event: OrchestratorStepEvent) => void): (() => void) => {
    const listener = (_event: IpcRendererEvent, payload: OrchestratorStepEvent) =>
      callback(payload);
    ipcRenderer.on('orchestrator:step', listener);
    return () => ipcRenderer.removeListener('orchestrator:step', listener);
  },
};

contextBridge.exposeInMainWorld('api', api);

export type MultiAgentApi = typeof api;
