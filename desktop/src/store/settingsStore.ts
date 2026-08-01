import { create } from 'zustand';
import type {
  AppSettings,
  HealthStatus,
  ModelInfo,
  Persona,
  ServerProfile,
  ThemeMode,
} from '../../../shared/types';
import { isLikelyImageModel } from '../../../shared/types';
import { cleanErrorMessage } from '../lib/errors';

function applyTheme(theme: ThemeMode): void {
  document.documentElement.setAttribute('data-theme', theme);
}

type SettingsState = {
  settings: AppSettings | null;
  health: HealthStatus | null;
  models: ModelInfo[];
  modelsError: string | null;
  loadedModels: string[];
  loadStatusSupported: boolean;
  personas: Persona[];
  settingsOpen: boolean;
  modelsOpen: boolean;
  loading: boolean;
  loadingModelId: string | null;
  load: () => Promise<void>;
  refreshHealth: () => Promise<void>;
  refreshModels: () => Promise<void>;
  refreshLoadedModels: () => Promise<void>;
  loadModel: (modelId: string) => Promise<void>;
  updateSettings: (partial: Partial<AppSettings>) => Promise<void>;
  setTheme: (theme: ThemeMode) => Promise<void>;
  setSettingsOpen: (open: boolean) => void;
  setModelsOpen: (open: boolean) => void;
  imageModels: () => ModelInfo[];
  addServer: (profile: Omit<ServerProfile, 'id'>) => Promise<void>;
  updateServerProfile: (id: string, patch: Partial<Omit<ServerProfile, 'id'>>) => Promise<void>;
  removeServer: (id: string) => Promise<void>;
  activateServer: (id: string) => Promise<void>;
};

function pickDefaults(
  settings: AppSettings,
  models: ModelInfo[],
): Promise<AppSettings> | AppSettings {
  const chatModels = models.filter((model) => !isLikelyImageModel(model.id));
  const imageModels = models.filter((model) => isLikelyImageModel(model.id));
  const patch: Partial<AppSettings> = {};
  if (!settings.model && (chatModels[0] || models[0])) {
    patch.model = (chatModels[0] || models[0]).id;
  }
  if (!settings.imageModel && (imageModels[0] || models[0])) {
    patch.imageModel = (imageModels[0] || models[0]).id;
  }
  if (Object.keys(patch).length) {
    return window.api.setSettings(patch);
  }
  return settings;
}

export const useSettingsStore = create<SettingsState>((set, get) => ({
  settings: null,
  health: null,
  models: [],
  modelsError: null,
  loadedModels: [],
  loadStatusSupported: true,
  personas: [],
  settingsOpen: false,
  modelsOpen: false,
  loading: false,
  loadingModelId: null,

  load: async () => {
    set({ loading: true });
    try {
      const [settings, personas, health] = await Promise.all([
        window.api.getSettings(),
        window.api.listPersonas(),
        window.api.checkHealth(),
      ]);

      let models: ModelInfo[] = [];
      let loadedModels: string[] = [];
      let loadStatusSupported = true;
      let modelsError: string | null = null;
      if (health.ok) {
        try {
          models = await window.api.listModels();
        } catch (err) {
          models = [];
          modelsError = cleanErrorMessage(err, 'Failed to load models');
        }
        try {
          const result = await window.api.listLoadedModels();
          loadedModels = result.names;
          loadStatusSupported = result.supported;
        } catch {
          loadedModels = [];
        }
      }

      const nextSettings = await pickDefaults(settings, models);
      applyTheme(nextSettings.theme);
      set({
        settings: nextSettings,
        personas,
        health,
        models,
        modelsError,
        loadedModels,
        loadStatusSupported,
        loading: false,
      });
    } catch {
      set({ loading: false });
    }
  },

  refreshHealth: async () => {
    const health = await window.api.checkHealth();
    set({ health });
    if (health.ok) {
      await get().refreshModels();
      await get().refreshLoadedModels();
    } else {
      set({ loadedModels: [] });
    }
  },

  refreshModels: async () => {
    try {
      const models = await window.api.listModels();
      const { settings } = get();
      const nextSettings = settings ? await pickDefaults(settings, models) : null;
      set({
        models,
        modelsError: null,
        ...(nextSettings ? { settings: nextSettings } : {}),
      });
    } catch (err) {
      set({ models: [], modelsError: cleanErrorMessage(err, 'Failed to load models') });
    }
  },

  refreshLoadedModels: async () => {
    try {
      const result = await window.api.listLoadedModels();
      set({ loadedModels: result.names, loadStatusSupported: result.supported });
    } catch {
      set({ loadedModels: [] });
    }
  },

  loadModel: async (modelId) => {
    const trimmed = modelId.trim();
    if (!trimmed || get().loadingModelId) return;
    set({ loadingModelId: trimmed });
    try {
      await window.api.loadModel(trimmed);
      await get().refreshLoadedModels();
    } finally {
      set({ loadingModelId: null });
    }
  },

  updateSettings: async (partial) => {
    const settings = await window.api.setSettings(partial);
    set({ settings });
    await get().refreshHealth();
  },

  setTheme: async (theme) => {
    applyTheme(theme);
    const settings = await window.api.setSettings({ theme });
    set({ settings });
  },

  setSettingsOpen: (open) => set({ settingsOpen: open }),
  setModelsOpen: (open) => set({ modelsOpen: open }),

  imageModels: () => get().models,

  addServer: async (profile) => {
    const settings = get().settings;
    if (!settings) return;
    const newProfile: ServerProfile = { ...profile, id: globalThis.crypto.randomUUID() };
    const servers = [...settings.servers, newProfile];
    const shouldActivate = !settings.servers.length;
    await get().updateSettings({
      servers,
      ...(shouldActivate
        ? {
            activeServerId: newProfile.id,
            providerType: newProfile.providerType,
            baseUrl: newProfile.baseUrl,
            apiKey: newProfile.apiKey,
            maxHistory: newProfile.maxHistory,
          }
        : {}),
    });
  },

  updateServerProfile: async (id, patch) => {
    const settings = get().settings;
    if (!settings) return;
    const updatedServers = settings.servers.map((server) =>
      server.id === id ? { ...server, ...patch } : server,
    );
    const isActive = settings.activeServerId === id;
    const updated = updatedServers.find((server) => server.id === id);
    await get().updateSettings({
      servers: updatedServers,
      ...(isActive && updated
        ? {
            providerType: updated.providerType,
            baseUrl: updated.baseUrl,
            apiKey: updated.apiKey,
            maxHistory: updated.maxHistory,
          }
        : {}),
    });
  },

  removeServer: async (id) => {
    const settings = get().settings;
    if (!settings) return;
    const servers = settings.servers.filter((server) => server.id !== id);
    const wasActive = settings.activeServerId === id;
    const nextActive = wasActive ? (servers[0] ?? null) : null;
    await get().updateSettings({
      servers,
      ...(wasActive
        ? {
            activeServerId: nextActive?.id ?? null,
            ...(nextActive
              ? {
                  providerType: nextActive.providerType,
                  baseUrl: nextActive.baseUrl,
                  apiKey: nextActive.apiKey,
                  maxHistory: nextActive.maxHistory,
                }
              : {}),
          }
        : {}),
    });
  },

  activateServer: async (id) => {
    const settings = get().settings;
    if (!settings) return;
    const profile = settings.servers.find((server) => server.id === id);
    if (!profile) return;
    await get().updateSettings({
      activeServerId: profile.id,
      providerType: profile.providerType,
      baseUrl: profile.baseUrl,
      apiKey: profile.apiKey,
      maxHistory: profile.maxHistory,
    });
  },
}));
