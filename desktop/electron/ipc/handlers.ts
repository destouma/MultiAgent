import { dialog, ipcMain, type BrowserWindow } from 'electron';
import fs from 'node:fs';
import type {
  AppSettings,
  ChatSendRequest,
  CreateConversationRequest,
  GenerateImageRequest,
} from '../../../shared/types';
import { encodeImageMessage } from '../../../shared/types';
import { createLlmClient } from '../../../shared/llm/createLlmClient';
import { ProviderError, type LlmClient } from '../../../shared/llm/types';
import { WorkspaceService } from '../../../shared/workspace/workspaceService';
import { ensureDefaultServer, getSettings, setSettings } from '../config';
import { ChatService } from '../services/chatService';
import { ConversationStore } from '../services/conversationStore';
import {
  formatConversationJson,
  formatConversationMarkdown,
  slugifyTitle,
  type ExportFormat,
} from '../services/exportFormat';
import { ImageService } from '../services/imageService';
import { PersonaRegistry } from '../services/personaRegistry';
import { IpcChannels } from './channels';

export type AppServices = {
  getClientFor: (serverId: string | null) => LlmClient;
  invalidateClients: () => void;
  store: ConversationStore;
  personas: PersonaRegistry;
  chat: ChatService;
  images: ImageService;
};

export async function createServices(getWindow: () => BrowserWindow | null): Promise<AppServices> {
  ensureDefaultServer();

  const clients = new Map<string, LlmClient>();
  const getClientFor = (serverId: string | null): LlmClient => {
    const settings = getSettings();
    const profile = serverId ? settings.servers.find((server) => server.id === serverId) : null;
    const key = profile?.id ?? '__active__';
    const effective = profile ?? settings;
    let client = clients.get(key);
    if (!client) {
      client = createLlmClient(effective.providerType, effective);
      clients.set(key, client);
    }
    return client;
  };
  const invalidateClients = () => {
    clients.clear();
  };

  const store = new ConversationStore();
  await store.ensureReady();
  const personas = new PersonaRegistry();
  personas.load();
  const images = new ImageService(getClientFor, () => getSettings().imageModel, getWindow);

  const chat = new ChatService(
    getClientFor,
    store,
    personas,
    images,
    () => getSettings(),
    getWindow,
  );

  return { getClientFor, invalidateClients, store, personas, chat, images };
}

export function registerIpcHandlers(
  services: AppServices,
  getWindow: () => BrowserWindow | null,
): void {
  const { getClientFor, invalidateClients, store, personas, chat, images } = services;
  const workspace = new WorkspaceService();

  ipcMain.handle(IpcChannels.settingsGet, () => getSettings());

  ipcMain.handle(IpcChannels.settingsSet, (_event, partial: Partial<AppSettings>) => {
    const next = setSettings(partial);
    // Provider type, connection details, or a saved server profile may have
    // changed; simplest and safest is to always drop every cached client
    // rather than track exactly which fields/profiles changed.
    invalidateClients();
    return next;
  });

  ipcMain.handle(IpcChannels.modelsList, async () => {
    try {
      return await getClientFor(null).listModels();
    } catch (error) {
      throw serializeError(error);
    }
  });

  ipcMain.handle(IpcChannels.modelsLoaded, async () => {
    try {
      const names = await getClientFor(null).listLoadedModelNames();
      return { names, supported: getClientFor(null).supportsLoadStatus() };
    } catch (error) {
      throw serializeError(error);
    }
  });

  ipcMain.handle(IpcChannels.modelsLoad, async (_event, model: string) => {
    try {
      const win = getWindow();
      await getClientFor(null).ensureModelLoaded(model, {
        onStatus: (message) => {
          const lower = message.toLowerCase();
          const phase = lower.includes('ready')
            ? 'ready'
            : lower.includes('loading') || lower.includes('waiting')
              ? 'loading'
              : 'checking';
          win?.webContents.send('model:status', { model, phase, message });
        },
      });
      return true;
    } catch (error) {
      throw serializeError(error);
    }
  });

  ipcMain.handle(IpcChannels.modelsListForServer, async (_event, serverId: string | null) => {
    try {
      return await getClientFor(serverId).listModels();
    } catch (error) {
      throw serializeError(error);
    }
  });

  ipcMain.handle(IpcChannels.modelsLoadedForServer, async (_event, serverId: string | null) => {
    try {
      const client = getClientFor(serverId);
      const names = await client.listLoadedModelNames();
      return { names, supported: client.supportsLoadStatus() };
    } catch (error) {
      throw serializeError(error);
    }
  });

  ipcMain.handle(IpcChannels.healthCheck, async () => getClientFor(null).checkHealth());

  ipcMain.handle(IpcChannels.healthCheckForServer, async (_event, serverId: string | null) =>
    getClientFor(serverId).checkHealth(),
  );

  ipcMain.handle(IpcChannels.personasList, () => personas.list());

  ipcMain.handle(IpcChannels.chatSend, async (_event, request: ChatSendRequest) => {
    try {
      return await chat.send(request);
    } catch (error) {
      throw serializeError(error);
    }
  });

  ipcMain.handle(IpcChannels.chatCancel, (_event, conversationId: string) =>
    chat.cancel(conversationId),
  );

  ipcMain.handle(IpcChannels.conversationsList, () => store.listConversations());

  ipcMain.handle(
    IpcChannels.conversationsCreate,
    (_event, request?: CreateConversationRequest | string) => {
      if (typeof request === 'string' || request == null) {
        return store.createConversation({ title: request });
      }
      return store.createConversation(request);
    },
  );

  ipcMain.handle(IpcChannels.conversationsRename, (_event, id: string, title: string) =>
    store.renameConversation(id, title),
  );

  ipcMain.handle(IpcChannels.conversationsSetModel, (_event, id: string, model: string | null) =>
    store.setConversationModel(id, model),
  );

  ipcMain.handle(
    IpcChannels.conversationsSetServer,
    (_event, id: string, serverId: string | null) => store.setConversationServer(id, serverId),
  );

  ipcMain.handle(IpcChannels.conversationsDelete, (_event, id: string) =>
    store.deleteConversation(id),
  );

  ipcMain.handle(IpcChannels.conversationsGet, (_event, id: string) => store.getConversation(id));

  ipcMain.handle(
    IpcChannels.conversationsExport,
    async (_event, id: string, format: ExportFormat) => {
      try {
        const conversation = store.getConversation(id);
        if (!conversation) {
          throw new ProviderError('unknown', 'Conversation not found');
        }
        const messages = store.getMessages(id);
        const content =
          format === 'json'
            ? formatConversationJson(conversation, messages)
            : formatConversationMarkdown(conversation, messages, personas.list());
        const extension = format === 'json' ? 'json' : 'md';
        const defaultPath = `${slugifyTitle(conversation.title)}.${extension}`;

        const win = getWindow();
        const result = win
          ? await dialog.showSaveDialog(win, {
              title: 'Export conversation',
              defaultPath,
              filters:
                format === 'json'
                  ? [{ name: 'JSON', extensions: ['json'] }]
                  : [{ name: 'Markdown', extensions: ['md'] }],
            })
          : await dialog.showSaveDialog({
              title: 'Export conversation',
              defaultPath,
              filters:
                format === 'json'
                  ? [{ name: 'JSON', extensions: ['json'] }]
                  : [{ name: 'Markdown', extensions: ['md'] }],
            });
        if (result.canceled || !result.filePath) return null;

        fs.writeFileSync(result.filePath, content, 'utf8');
        return result.filePath;
      } catch (error) {
        throw serializeError(error);
      }
    },
  );

  ipcMain.handle(IpcChannels.messagesList, (_event, conversationId: string) =>
    store.getMessages(conversationId),
  );

  ipcMain.handle(
    IpcChannels.messagesDeleteFrom,
    (_event, conversationId: string, messageId: string) =>
      store.deleteMessagesFrom(conversationId, messageId),
  );

  ipcMain.handle(IpcChannels.searchQuery, (_event, term: string) => store.search(term));

  ipcMain.handle(IpcChannels.checkpointsDiff, (_event, checkpointId: string) => {
    const checkpoint = store.getCheckpoint(checkpointId);
    if (!checkpoint) {
      throw new ProviderError('unknown', 'Checkpoint not found');
    }
    const conversation = store.getConversation(checkpoint.conversationId);
    if (!conversation?.workspacePath) {
      throw new ProviderError('unknown', 'This chat no longer has a workspace folder bound to it');
    }
    return {
      path: checkpoint.relativePath,
      before: checkpoint.previousExisted ? checkpoint.previousContent : null,
      after: workspace.tryReadFile(conversation.workspacePath, checkpoint.relativePath),
    };
  });

  ipcMain.handle(IpcChannels.checkpointsRevert, (_event, checkpointId: string) => {
    try {
      const checkpoint = store.getCheckpoint(checkpointId);
      if (!checkpoint) {
        throw new ProviderError('unknown', 'Checkpoint not found');
      }
      const conversation = store.getConversation(checkpoint.conversationId);
      if (!conversation?.workspacePath) {
        throw new ProviderError(
          'unknown',
          'This chat no longer has a workspace folder bound to it',
        );
      }
      if (checkpoint.previousExisted) {
        workspace.writeFile(
          conversation.workspacePath,
          checkpoint.relativePath,
          checkpoint.previousContent ?? '',
        );
      } else if (
        workspace.tryReadFile(conversation.workspacePath, checkpoint.relativePath) !== null
      ) {
        // The op that created this checkpoint created the file from
        // scratch; undoing it means removing the file, but only if it's
        // still there (a later op may have already deleted it).
        workspace.deleteFile(conversation.workspacePath, checkpoint.relativePath);
      }
      return true;
    } catch (error) {
      throw serializeError(error);
    }
  });

  ipcMain.handle(IpcChannels.foldersList, () => store.listFolders());

  ipcMain.handle(IpcChannels.foldersOpen, async () => {
    const selected = await pickFolderDialog(getWindow);
    if (!selected) return null;
    return store.addFolder(selected);
  });

  ipcMain.handle(IpcChannels.foldersRemove, (_event, folderPath: string) =>
    store.removeFolder(folderPath),
  );

  ipcMain.handle(IpcChannels.imagesGenerate, async (_event, request: GenerateImageRequest) => {
    try {
      const conversation = store.getConversation(request.conversationId);
      if (!conversation) {
        throw new ProviderError('unknown', 'Conversation not found');
      }

      const info = await images.generate(
        { ...request, model: request.model || conversation.model || undefined },
        conversation.workspacePath,
        conversation.serverId,
      );

      store.addMessage({
        conversationId: request.conversationId,
        role: 'user',
        content: `Generate image: ${request.prompt}`,
      });

      const message = store.addMessage({
        conversationId: request.conversationId,
        role: 'assistant',
        content: encodeImageMessage({
          id: info.id,
          prompt: info.prompt,
          model: info.model,
          size: info.size,
          fileName: info.fileName,
          workspaceRelativePath: info.workspaceRelativePath,
        }),
        personaId: null,
      });

      return { info, message };
    } catch (error) {
      throw serializeError(error);
    }
  });

  ipcMain.handle(IpcChannels.imagesGetDataUrl, (_event, fileName: string) => {
    try {
      return images.getDataUrl(fileName);
    } catch (error) {
      throw serializeError(error);
    }
  });

  ipcMain.handle(
    IpcChannels.imagesDownload,
    async (_event, fileName: string, defaultName?: string) => {
      try {
        return await images.download(fileName, defaultName);
      } catch (error) {
        throw serializeError(error);
      }
    },
  );

  ipcMain.handle(
    IpcChannels.imagesSaveToWorkspace,
    (_event, conversationId: string, fileName: string, relativePath?: string) => {
      try {
        const conversation = store.getConversation(conversationId);
        if (!conversation?.workspacePath) {
          throw new ProviderError(
            'unknown',
            'This chat has no workspace folder. Create a new chat with a folder, or download the image instead.',
          );
        }
        return images.saveToWorkspace(fileName, conversation.workspacePath, relativePath);
      } catch (error) {
        throw serializeError(error);
      }
    },
  );
}

async function pickFolderDialog(getWindow: () => BrowserWindow | null): Promise<string | null> {
  const win = getWindow();
  const result = win
    ? await dialog.showOpenDialog(win, {
        title: 'Choose a folder',
        properties: ['openDirectory'],
      })
    : await dialog.showOpenDialog({
        title: 'Choose a folder',
        properties: ['openDirectory'],
      });
  if (result.canceled || !result.filePaths[0]) return null;
  return result.filePaths[0];
}

function serializeError(error: unknown): Error {
  if (error instanceof ProviderError) {
    const err = new Error(error.message);
    err.name = error.code;
    return err;
  }
  if (error instanceof Error) return error;
  return new Error(String(error));
}
