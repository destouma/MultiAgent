import { dialog, ipcMain, type BrowserWindow } from 'electron';
import type {
  AppSettings,
  ChatSendRequest,
  CreateConversationRequest,
  GenerateImageRequest,
} from '../../../shared/types';
import { encodeImageMessage } from '../../../shared/types';
import { getSettings, setSettings } from '../config';
import { ChatService } from '../services/chatService';
import { ConversationStore } from '../services/conversationStore';
import { ImageService } from '../services/imageService';
import { LemonadeClient, LemonadeError } from '../services/lemonadeClient';
import { PersonaRegistry } from '../services/personaRegistry';
import { IpcChannels } from './channels';

export type AppServices = {
  lemonade: LemonadeClient;
  store: ConversationStore;
  personas: PersonaRegistry;
  chat: ChatService;
  images: ImageService;
};

export async function createServices(getWindow: () => BrowserWindow | null): Promise<AppServices> {
  const settings = getSettings();
  const lemonade = new LemonadeClient(settings);
  const store = new ConversationStore();
  await store.ensureReady();
  const personas = new PersonaRegistry();
  personas.load();
  const images = new ImageService(lemonade, () => getSettings().imageModel, getWindow);

  const chat = new ChatService(lemonade, store, personas, images, () => getSettings(), getWindow);

  return { lemonade, store, personas, chat, images };
}

export function registerIpcHandlers(
  services: AppServices,
  getWindow: () => BrowserWindow | null,
): void {
  const { lemonade, store, personas, chat, images } = services;

  ipcMain.handle(IpcChannels.settingsGet, () => getSettings());

  ipcMain.handle(IpcChannels.settingsSet, (_event, partial: Partial<AppSettings>) => {
    const next = setSettings(partial);
    lemonade.updateSettings(next);
    return next;
  });

  ipcMain.handle(IpcChannels.modelsList, async () => {
    try {
      return await lemonade.listModels();
    } catch (error) {
      throw serializeError(error);
    }
  });

  ipcMain.handle(IpcChannels.modelsLoaded, async () => {
    try {
      return await lemonade.listLoadedModelNames();
    } catch (error) {
      throw serializeError(error);
    }
  });

  ipcMain.handle(IpcChannels.modelsLoad, async (_event, model: string) => {
    try {
      const win = getWindow();
      await lemonade.ensureModelLoaded(model, {
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

  ipcMain.handle(IpcChannels.healthCheck, async () => lemonade.checkHealth());

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
        throw new LemonadeError('unknown', 'Conversation not found');
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
          throw new LemonadeError(
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
  if (error instanceof LemonadeError) {
    const err = new Error(error.message);
    err.name = error.code;
    return err;
  }
  if (error instanceof Error) return error;
  return new Error(String(error));
}
