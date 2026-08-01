import { dialog, ipcMain, type BrowserWindow } from 'electron';
import type {
  AppSettings,
  ChatSendRequest,
  CreateConversationRequest,
  GenerateImageRequest,
} from '../../../shared/types';
import { encodeImageMessage } from '../../../shared/types';
import { createLlmClient } from '../../../shared/llm/createLlmClient';
import { ProviderError, type LlmClient } from '../../../shared/llm/types';
import { ensureDefaultServer, getSettings, setSettings } from '../config';
import { ChatService } from '../services/chatService';
import { ConversationStore } from '../services/conversationStore';
import { ImageService } from '../services/imageService';
import { PersonaRegistry } from '../services/personaRegistry';
import { IpcChannels } from './channels';

export type AppServices = {
  getClient: () => LlmClient;
  setClient: (next: LlmClient) => void;
  store: ConversationStore;
  personas: PersonaRegistry;
  chat: ChatService;
  images: ImageService;
};

export async function createServices(getWindow: () => BrowserWindow | null): Promise<AppServices> {
  const settings = ensureDefaultServer();
  let client = createLlmClient(settings.providerType, settings);
  const getClient = () => client;
  const setClient = (next: LlmClient) => {
    client = next;
  };

  const store = new ConversationStore();
  await store.ensureReady();
  const personas = new PersonaRegistry();
  personas.load();
  const images = new ImageService(getClient, () => getSettings().imageModel, getWindow);

  const chat = new ChatService(getClient, store, personas, images, () => getSettings(), getWindow);

  return { getClient, setClient, store, personas, chat, images };
}

export function registerIpcHandlers(
  services: AppServices,
  getWindow: () => BrowserWindow | null,
): void {
  const { getClient, setClient, store, personas, chat, images } = services;

  ipcMain.handle(IpcChannels.settingsGet, () => getSettings());

  ipcMain.handle(IpcChannels.settingsSet, (_event, partial: Partial<AppSettings>) => {
    const next = setSettings(partial);
    // Provider type or connection details may have changed; simplest and
    // safest is to always build a fresh client rather than track exactly
    // which fields changed.
    setClient(createLlmClient(next.providerType, next));
    return next;
  });

  ipcMain.handle(IpcChannels.modelsList, async () => {
    try {
      return await getClient().listModels();
    } catch (error) {
      throw serializeError(error);
    }
  });

  ipcMain.handle(IpcChannels.modelsLoaded, async () => {
    try {
      const names = await getClient().listLoadedModelNames();
      return { names, supported: getClient().supportsLoadStatus() };
    } catch (error) {
      throw serializeError(error);
    }
  });

  ipcMain.handle(IpcChannels.modelsLoad, async (_event, model: string) => {
    try {
      const win = getWindow();
      await getClient().ensureModelLoaded(model, {
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

  ipcMain.handle(IpcChannels.healthCheck, async () => getClient().checkHealth());

  ipcMain.handle(IpcChannels.personasList, () => personas.list());

  ipcMain.handle(IpcChannels.chatSend, async (_event, request: ChatSendRequest) => {
    try {
      return await chat.send(request);
    } catch (error) {
      throw serializeError(error);
    }
  });

  ipcMain.handle(IpcChannels.chatCancel, () => chat.cancel());

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

  ipcMain.handle(IpcChannels.conversationsDelete, (_event, id: string) =>
    store.deleteConversation(id),
  );

  ipcMain.handle(IpcChannels.conversationsGet, (_event, id: string) => store.getConversation(id));

  ipcMain.handle(IpcChannels.messagesList, (_event, conversationId: string) =>
    store.getMessages(conversationId),
  );

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
