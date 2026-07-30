import { create } from 'zustand';
import type {
  AppSettings,
  HealthStatus,
  ModelInfo,
  Persona,
  ThemeMode,
} from '../../../shared/types';
import { isLikelyImageModel } from '../../../shared/types';

function applyTheme(theme: ThemeMode): void {
  document.documentElement.setAttribute('data-theme', theme);
}

type SettingsState = {
  settings: AppSettings | null;
  health: HealthStatus | null;
  models: ModelInfo[];
  loadedModels: string[];
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
  loadedModels: [],
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
      if (health.ok) {
        try {
          models = await window.api.listModels();
        } catch {
          models = [];
        }
        try {
          loadedModels = await window.api.listLoadedModels();
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
        loadedModels,
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
      set({ models, ...(nextSettings ? { settings: nextSettings } : {}) });
    } catch {
      set({ models: [] });
    }
  },

  refreshLoadedModels: async () => {
    try {
      const loadedModels = await window.api.listLoadedModels();
      set({ loadedModels });
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
}));
