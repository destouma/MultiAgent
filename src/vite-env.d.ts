/// <reference types="vite/client" />

import type { MultiAgentApi } from '../electron/preload';

declare global {
  interface Window {
    api: MultiAgentApi;
  }
}

export {};
