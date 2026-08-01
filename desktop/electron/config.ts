import Store from 'electron-store';
import type { AppSettings } from '../../shared/types';

const defaults: AppSettings = {
  baseUrl: 'http://localhost:13305/api/v1',
  apiKey: 'lemonade',
  model: '',
  imageModel: '',
  maxHistory: 40,
  theme: 'light',
  providerType: 'openai',
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
