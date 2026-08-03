import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import initSqlJs from 'sql.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('electron', () => ({
  app: {
    getPath: () => os.tmpdir(),
    isPackaged: false,
  },
}));

const { ConversationStore } = await import('./conversationStore');

function tmpDbPath(): string {
  return path.join(os.tmpdir(), `ma-store-test-${randomUUID()}.db`);
}

function wasmPath(): string {
  return path.join(process.cwd(), 'node_modules', 'sql.js', 'dist', 'sql-wasm.wasm');
}

describe('ConversationStore folders', () => {
  let dbPath: string;
  let store: InstanceType<typeof ConversationStore>;

  beforeEach(() => {
    dbPath = tmpDbPath();
  });

  afterEach(() => {
    store?.close();
    for (const candidate of [dbPath, `${dbPath}.${process.pid}.tmp`]) {
      fs.rmSync(candidate, { force: true });
    }
  });

  it('starts with no folders', async () => {
    store = new ConversationStore(dbPath);
    await store.ensureReady();
    expect(store.listFolders()).toEqual([]);
  });

  it('adds a folder and lists it', async () => {
    store = new ConversationStore(dbPath);
    await store.ensureReady();

    const added = store.addFolder('C:\\work\\project');
    expect(added.path).toBe('C:\\work\\project');
    expect(typeof added.addedAt).toBe('number');

    expect(store.listFolders()).toEqual([added]);
  });

  it('is idempotent: adding the same folder path twice does not duplicate or change addedAt', async () => {
    store = new ConversationStore(dbPath);
    await store.ensureReady();

    const first = store.addFolder('C:\\work\\project');
    const second = store.addFolder('C:\\work\\project');

    expect(second).toEqual(first);
    expect(store.listFolders()).toHaveLength(1);
  });

  it('lists multiple folders ordered by when they were added', async () => {
    store = new ConversationStore(dbPath);
    await store.ensureReady();

    store.addFolder('C:\\a');
    store.addFolder('C:\\b');
    store.addFolder('C:\\c');

    expect(store.listFolders().map((f) => f.path)).toEqual(['C:\\a', 'C:\\b', 'C:\\c']);
  });

  it('backfills folders from pre-existing conversations.workspacePath on first migration', async () => {
    // Simulate a database created by a version of the app that predates the
    // folders table: conversations already have workspacePath set, but no
    // folders table exists yet.
    const loader =
      typeof initSqlJs === 'function'
        ? initSqlJs
        : (initSqlJs as unknown as { default: typeof initSqlJs }).default;
    const SQL = await loader({ locateFile: () => wasmPath() });
    const legacyDb = new SQL.Database();
    legacyDb.run(`
      CREATE TABLE conversations (
        id TEXT PRIMARY KEY,
        title TEXT NOT NULL,
        createdAt INTEGER NOT NULL,
        updatedAt INTEGER NOT NULL,
        workspacePath TEXT,
        kind TEXT DEFAULT 'chat'
      );
    `);
    legacyDb.run(`
      CREATE TABLE messages (
        id TEXT PRIMARY KEY,
        conversationId TEXT NOT NULL,
        role TEXT NOT NULL,
        content TEXT NOT NULL,
        personaId TEXT,
        createdAt INTEGER NOT NULL
      );
    `);
    legacyDb.run(
      `INSERT INTO conversations (id, title, createdAt, updatedAt, workspacePath, kind) VALUES
       (?, 'Old chat', 100, 100, 'C:\\legacy\\folder', 'chat'),
       (?, 'Old chat 2', 50, 200, 'C:\\legacy\\folder', 'chat'),
       (?, 'No folder chat', 10, 10, NULL, 'chat')`,
      [randomUUID(), randomUUID(), randomUUID()],
    );
    fs.writeFileSync(dbPath, Buffer.from(legacyDb.export()));
    legacyDb.close();

    store = new ConversationStore(dbPath);
    await store.ensureReady();

    const folders = store.listFolders();
    expect(folders).toHaveLength(1);
    expect(folders[0].path).toBe('C:\\legacy\\folder');
    // Backfilled addedAt should be the earliest createdAt among that
    // folder's conversations, not "now".
    expect(folders[0].addedAt).toBe(50);
  });

  it('does not re-run the backfill once the folders table already exists', async () => {
    store = new ConversationStore(dbPath);
    await store.ensureReady();
    store.addFolder('C:\\explicit');
    store.close();

    // Re-open the same database file; folders table already exists this
    // time, so re-migrating must not touch it. Reassign `store` so afterEach
    // closes this instance instead of the already-closed first one.
    store = new ConversationStore(dbPath);
    await store.ensureReady();
    expect(store.listFolders().map((f) => f.path)).toEqual(['C:\\explicit']);
  });

  it('removes a folder and clears workspacePath on bound conversations', async () => {
    store = new ConversationStore(dbPath);
    await store.ensureReady();

    store.addFolder('C:\\work\\project');
    const bound = store.createConversation({
      title: 'Bound chat',
      workspacePath: 'C:\\work\\project',
      kind: 'chat',
    });
    const other = store.createConversation({
      title: 'Other',
      workspacePath: 'C:\\other',
      kind: 'chat',
    });

    expect(store.removeFolder('C:\\work\\project')).toBe(true);
    expect(store.listFolders()).toEqual([]);
    expect(store.getConversation(bound.id)?.workspacePath).toBeNull();
    expect(store.getConversation(other.id)?.workspacePath).toBe('C:\\other');
    expect(store.removeFolder('C:\\work\\project')).toBe(false);
  });
});

describe('ConversationStore per-conversation model', () => {
  let dbPath: string;
  let store: InstanceType<typeof ConversationStore>;

  beforeEach(() => {
    dbPath = tmpDbPath();
  });

  afterEach(() => {
    store?.close();
    for (const candidate of [dbPath, `${dbPath}.${process.pid}.tmp`]) {
      fs.rmSync(candidate, { force: true });
    }
  });

  it('starts with no model set', async () => {
    store = new ConversationStore(dbPath);
    await store.ensureReady();

    const conversation = store.createConversation({ kind: 'chat' });
    expect(conversation.model).toBeNull();
    expect(store.getConversation(conversation.id)?.model).toBeNull();
  });

  it('setConversationModel updates and persists the model', async () => {
    store = new ConversationStore(dbPath);
    await store.ensureReady();

    const conversation = store.createConversation({ kind: 'chat' });
    const updated = store.setConversationModel(conversation.id, 'llama-3-8b');

    expect(updated?.model).toBe('llama-3-8b');
    expect(store.getConversation(conversation.id)?.model).toBe('llama-3-8b');
    expect(store.listConversations().find((c) => c.id === conversation.id)?.model).toBe(
      'llama-3-8b',
    );
  });

  it('can clear a conversation model back to null', async () => {
    store = new ConversationStore(dbPath);
    await store.ensureReady();

    const conversation = store.createConversation({ kind: 'chat' });
    store.setConversationModel(conversation.id, 'llama-3-8b');
    const cleared = store.setConversationModel(conversation.id, null);

    expect(cleared?.model).toBeNull();
  });

  it('keeps each conversation model independent, even across kinds', async () => {
    store = new ConversationStore(dbPath);
    await store.ensureReady();

    const chat = store.createConversation({ kind: 'chat' });
    const orchestrator = store.createConversation({ kind: 'orchestrator' });
    const image = store.createConversation({ kind: 'image' });

    store.setConversationModel(chat.id, 'chat-model');
    store.setConversationModel(orchestrator.id, 'orchestrator-model');
    store.setConversationModel(image.id, 'image-model');

    expect(store.getConversation(chat.id)?.model).toBe('chat-model');
    expect(store.getConversation(orchestrator.id)?.model).toBe('orchestrator-model');
    expect(store.getConversation(image.id)?.model).toBe('image-model');

    // Changing one must not affect the others.
    store.setConversationModel(chat.id, 'chat-model-2');
    expect(store.getConversation(chat.id)?.model).toBe('chat-model-2');
    expect(store.getConversation(orchestrator.id)?.model).toBe('orchestrator-model');
    expect(store.getConversation(image.id)?.model).toBe('image-model');
  });
});

describe('ConversationStore per-conversation server', () => {
  let dbPath: string;
  let store: InstanceType<typeof ConversationStore>;

  beforeEach(() => {
    dbPath = tmpDbPath();
  });

  afterEach(() => {
    store?.close();
    for (const candidate of [dbPath, `${dbPath}.${process.pid}.tmp`]) {
      fs.rmSync(candidate, { force: true });
    }
  });

  it('starts with no server set', async () => {
    store = new ConversationStore(dbPath);
    await store.ensureReady();

    const conversation = store.createConversation({ kind: 'chat' });
    expect(conversation.serverId).toBeNull();
    expect(store.getConversation(conversation.id)?.serverId).toBeNull();
  });

  it('setConversationServer updates and persists the server', async () => {
    store = new ConversationStore(dbPath);
    await store.ensureReady();

    const conversation = store.createConversation({ kind: 'chat' });
    const updated = store.setConversationServer(conversation.id, 'server-a');

    expect(updated?.serverId).toBe('server-a');
    expect(store.getConversation(conversation.id)?.serverId).toBe('server-a');
    expect(store.listConversations().find((c) => c.id === conversation.id)?.serverId).toBe(
      'server-a',
    );
  });

  it('can clear a conversation server back to null', async () => {
    store = new ConversationStore(dbPath);
    await store.ensureReady();

    const conversation = store.createConversation({ kind: 'chat' });
    store.setConversationServer(conversation.id, 'server-a');
    const cleared = store.setConversationServer(conversation.id, null);

    expect(cleared?.serverId).toBeNull();
  });

  it('keeps each conversation server independent', async () => {
    store = new ConversationStore(dbPath);
    await store.ensureReady();

    const left = store.createConversation({ kind: 'chat' });
    const right = store.createConversation({ kind: 'chat' });

    store.setConversationServer(left.id, 'server-a');
    store.setConversationServer(right.id, 'server-b');

    expect(store.getConversation(left.id)?.serverId).toBe('server-a');
    expect(store.getConversation(right.id)?.serverId).toBe('server-b');

    store.setConversationServer(left.id, 'server-c');
    expect(store.getConversation(left.id)?.serverId).toBe('server-c');
    expect(store.getConversation(right.id)?.serverId).toBe('server-b');
  });
});
