import { create } from 'zustand';
import type { AppSettings, HealthStatus, ModelInfo, Persona } from '../../shared/types';
import { isLikelyImageModel } from '../../shared/types';

type SettingsState = {
  settings: AppSettings | null;
  health: HealthStatus | null;
  models: ModelInfo[];
  personas: Persona[];
  settingsOpen: boolean;
  loading: boolean;
  load: () => Promise<void>;
  refreshHealth: () => Promise<void>;
  refreshModels: () => Promise<void>;
  updateSettings: (partial: Partial<AppSettings>) => Promise<void>;
  setSettingsOpen: (open: boolean) => void;
  setModel: (model: string) => Promise<void>;
  setImageModel: (imageModel: string) => Promise<void>;
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
  personas: [],
  settingsOpen: false,
  loading: false,

  load: async () => {
    set({ loading: true });
    try {
      const [settings, personas, health] = await Promise.all([
        window.api.getSettings(),
        window.api.listPersonas(),
        window.api.checkHealth(),
      ]);

      let models: ModelInfo[] = [];
      if (health.ok) {
        try {
          models = await window.api.listModels();
        } catch {
          models = [];
        }
      }

      const nextSettings = await pickDefaults(settings, models);
      set({ settings: nextSettings, personas, health, models, loading: false });
    } catch {
      set({ loading: false });
    }
  },

  refreshHealth: async () => {
    const health = await window.api.checkHealth();
    set({ health });
    if (health.ok) {
      await get().refreshModels();
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

  updateSettings: async (partial) => {
    const settings = await window.api.setSettings(partial);
    set({ settings });
    await get().refreshHealth();
  },

  setSettingsOpen: (open) => set({ settingsOpen: open }),

  setModel: async (model) => {
    const settings = await window.api.setSettings({ model });
    set({ settings });
  },

  setImageModel: async (imageModel) => {
    const settings = await window.api.setSettings({ imageModel });
    set({ settings });
  },

  imageModels: () => get().models,
}));
