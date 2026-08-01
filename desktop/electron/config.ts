import { randomUUID } from 'node:crypto';
import Store from 'electron-store';
import type { AppSettings } from '../../shared/types';

const defaults: AppSettings = {
  baseUrl: 'http://localhost:13305/api/v1',
  apiKey: 'local-llm',
  model: '',
  imageModel: '',
  maxHistory: 40,
  theme: 'light',
  providerType: 'lemonade',
  servers: [],
  activeServerId: null,
};

const store = new Store<AppSettings>({
  name: 'config',
  defaults,
});

export function getSettings(): AppSettings {
  return {
    baseUrl: store.get('baseUrl', defaults.baseUrl),
    apiKey: store.get('apiKey', defaults.apiKey),
    model: store.get('model', defaults.model),
    imageModel: store.get('imageModel', defaults.imageModel),
    maxHistory: store.get('maxHistory', defaults.maxHistory),
    theme: store.get('theme', defaults.theme),
    providerType: store.get('providerType', defaults.providerType),
    servers: store.get('servers', defaults.servers),
    activeServerId: store.get('activeServerId', defaults.activeServerId),
  };
}

export function setSettings(partial: Partial<AppSettings>): AppSettings {
  const next = { ...getSettings(), ...partial };
  store.set(next);
  return next;
}

export function getDefaults(): AppSettings {
  return { ...defaults };
}

// Seeds one server profile from the current active connection fields so
// existing users' configured connection isn't lost when this feature ships.
export function ensureDefaultServer(): AppSettings {
  const settings = getSettings();
  if (settings.servers.length) return settings;
  const profile = {
    id: randomUUID(),
    name: 'Default',
    providerType: settings.providerType,
    baseUrl: settings.baseUrl,
    apiKey: settings.apiKey,
    maxHistory: settings.maxHistory,
  };
  return setSettings({ servers: [profile], activeServerId: profile.id });
}
