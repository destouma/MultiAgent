type Persona = {
  id: string;
  name: string;
  description: string;
  systemPrompt: string;
  defaultModel?: string;
  color: string;
};

type ChatMessage = {
  id: string;
  conversationId: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  personaId: string | null;
  createdAt: number;
};

type ModelInfo = {
  id: string;
  ownedBy?: string;
};

type ExtensionToWebviewMessage =
  | { type: 'init'; personas: Persona[]; personaId: string; history: ChatMessage[] }
  | { type: 'health'; ok: boolean; message: string }
  | { type: 'models'; models: ModelInfo[]; model: string }
  | { type: 'userMessage'; message: ChatMessage }
  | { type: 'token'; messageId: string; delta: string }
  | { type: 'done'; messageId: string; content: string; personaId: string }
  | { type: 'error'; messageId: string; message: string }
  | { type: 'busy'; busy: boolean }
  | { type: 'workspaceStatus'; enabled: boolean; folderName: string | null }
  | {
      type: 'workspaceOp';
      op: string;
      path: string;
      status: 'running' | 'ok' | 'error';
      detail?: string;
    };

declare function acquireVsCodeApi(): {
  postMessage: (message: unknown) => void;
  getState: () => unknown;
  setState: (state: unknown) => void;
};

const vscode = acquireVsCodeApi();

const root = document.getElementById('root')!;
root.innerHTML = `
  <div class="ma-app">
    <div class="ma-toolbar">
      <div class="ma-toolbar__row">
        <span id="ma-status" class="ma-status ma-status--unknown" title="Connecting…">●</span>
        <select id="ma-persona"></select>
      </div>
      <div class="ma-toolbar__row">
        <select id="ma-model"></select>
      </div>
      <div class="ma-toolbar__row">
        <span id="ma-workspace" class="ma-workspace" hidden></span>
      </div>
    </div>
    <div id="ma-messages" class="ma-messages"></div>
    <div class="ma-composer">
      <textarea id="ma-input" rows="2" placeholder="Message MultiAgent…"></textarea>
      <div class="ma-composer__buttons">
        <button id="ma-send">Send</button>
        <button id="ma-stop" disabled>Stop</button>
      </div>
    </div>
  </div>
`;

const statusEl = document.getElementById('ma-status') as HTMLSpanElement;
const personaSelect = document.getElementById('ma-persona') as HTMLSelectElement;
const modelSelect = document.getElementById('ma-model') as HTMLSelectElement;
const workspaceEl = document.getElementById('ma-workspace') as HTMLSpanElement;
const messagesEl = document.getElementById('ma-messages') as HTMLDivElement;
const inputEl = document.getElementById('ma-input') as HTMLTextAreaElement;
const sendBtn = document.getElementById('ma-send') as HTMLButtonElement;
const stopBtn = document.getElementById('ma-stop') as HTMLButtonElement;

let personas: Persona[] = [];
const streamingBodies = new Map<string, HTMLDivElement>();
const pendingToolOps = new Map<string, HTMLDivElement>();

function personaColor(id: string | null): string {
  return personas.find((persona) => persona.id === id)?.color ?? '#888888';
}

function personaName(id: string | null): string {
  return personas.find((persona) => persona.id === id)?.name ?? 'Assistant';
}

function appendMessage(message: ChatMessage): HTMLDivElement {
  const el = document.createElement('div');
  el.className = `ma-message ma-message--${message.role}`;

  const label = document.createElement('div');
  label.className = 'ma-message__label';
  if (message.role === 'assistant') {
    label.style.color = personaColor(message.personaId);
  }
  label.textContent = message.role === 'user' ? 'You' : personaName(message.personaId);

  const body = document.createElement('div');
  body.className = 'ma-message__body';
  body.textContent = message.content;

  el.append(label, body);
  messagesEl.append(el);
  messagesEl.scrollTop = messagesEl.scrollHeight;
  return el;
}

function appendError(text: string): void {
  const el = document.createElement('div');
  el.className = 'ma-message ma-message--error';
  el.textContent = text;
  messagesEl.append(el);
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

function setBusy(busy: boolean): void {
  sendBtn.disabled = busy;
  stopBtn.disabled = !busy;
  inputEl.disabled = busy;
}

function send(): void {
  const text = inputEl.value;
  if (!text.trim()) return;
  vscode.postMessage({ type: 'send', text, personaId: personaSelect.value });
  inputEl.value = '';
}

sendBtn.addEventListener('click', send);
stopBtn.addEventListener('click', () => vscode.postMessage({ type: 'cancel' }));
modelSelect.addEventListener('change', () => {
  vscode.postMessage({ type: 'setModel', model: modelSelect.value });
});
inputEl.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    send();
  }
});

window.addEventListener('message', (event: MessageEvent<ExtensionToWebviewMessage>) => {
  const message = event.data;
  switch (message.type) {
    case 'init': {
      personas = message.personas;
      personaSelect.innerHTML = '';
      for (const persona of personas) {
        const option = document.createElement('option');
        option.value = persona.id;
        option.textContent = persona.name;
        option.title = persona.description;
        personaSelect.append(option);
      }
      personaSelect.value = message.personaId;
      messagesEl.innerHTML = '';
      for (const historyMessage of message.history) {
        appendMessage(historyMessage);
      }
      break;
    }
    case 'health':
      statusEl.className = `ma-status ${message.ok ? 'ma-status--ok' : 'ma-status--error'}`;
      statusEl.title = message.message;
      break;
    case 'models': {
      modelSelect.innerHTML = '';
      if (!message.models.length) {
        const option = document.createElement('option');
        option.value = '';
        option.textContent = 'No models found';
        modelSelect.append(option);
      } else {
        for (const model of message.models) {
          const option = document.createElement('option');
          option.value = model.id;
          option.textContent = model.id;
          modelSelect.append(option);
        }
      }
      modelSelect.value = message.model;
      break;
    }
    case 'userMessage':
      appendMessage(message.message);
      break;
    case 'token': {
      let el = streamingBodies.get(message.messageId);
      if (!el) {
        const messageEl = appendMessage({
          id: message.messageId,
          conversationId: 'default',
          role: 'assistant',
          content: '',
          personaId: personaSelect.value,
          createdAt: Date.now(),
        });
        el = messageEl.querySelector('.ma-message__body') as HTMLDivElement;
        streamingBodies.set(message.messageId, el);
      }
      el.textContent += message.delta;
      messagesEl.scrollTop = messagesEl.scrollHeight;
      break;
    }
    case 'done':
      streamingBodies.delete(message.messageId);
      break;
    case 'error':
      appendError(message.message);
      streamingBodies.delete(message.messageId);
      break;
    case 'busy':
      setBusy(message.busy);
      break;
    case 'workspaceStatus':
      if (message.enabled && message.folderName) {
        workspaceEl.hidden = false;
        workspaceEl.textContent = `📁 ${message.folderName}`;
        workspaceEl.title = `Workspace tools enabled for "${message.folderName}"`;
      } else {
        workspaceEl.hidden = true;
      }
      break;
    case 'workspaceOp': {
      const key = `${message.op}|${message.path}`;
      if (message.status === 'running') {
        const el = document.createElement('div');
        el.className = 'ma-tool-op ma-tool-op--running';
        el.textContent = `→ ${message.op}(${message.path})`;
        messagesEl.append(el);
        messagesEl.scrollTop = messagesEl.scrollHeight;
        pendingToolOps.set(key, el);
      } else {
        const el = pendingToolOps.get(key);
        if (el) {
          el.className = `ma-tool-op ma-tool-op--${message.status}`;
          el.textContent = `→ ${message.op}(${message.path})${message.detail ? `: ${message.detail}` : ''}`;
          pendingToolOps.delete(key);
        }
      }
      break;
    }
  }
});

vscode.postMessage({ type: 'ready' });
