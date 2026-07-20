import initSqlJs, { type Database, type SqlJsStatic } from 'sql.js';
import { app } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import type {
  ChatMessage,
  Conversation,
  ConversationKind,
  CreateConversationRequest,
  MessageRole,
} from '../../shared/types';

function wasmPath(): string {
  if (app.isPackaged) {
    return path.join(process.resourcesPath, 'sql-wasm.wasm');
  }
  return path.join(
    process.cwd(),
    'node_modules',
    'sql.js',
    'dist',
    'sql-wasm.wasm',
  );
}

export class ConversationStore {
  private db!: Database;
  private SQL!: SqlJsStatic;
  private filePath: string;
  private ready: Promise<void>;

  constructor(dbPath?: string) {
    const userData = app.getPath('userData');
    fs.mkdirSync(userData, { recursive: true });
    this.filePath = dbPath ?? path.join(userData, 'chats.db');
    this.ready = this.init();
  }

  private async init(): Promise<void> {
    const loader =
      typeof initSqlJs === 'function'
        ? initSqlJs
        : ((initSqlJs as unknown as { default: typeof initSqlJs }).default);

    this.SQL = await loader({
      locateFile: () => wasmPath(),
    });

    if (fs.existsSync(this.filePath)) {
      const fileBuffer = fs.readFileSync(this.filePath);
      this.db = new this.SQL.Database(fileBuffer);
    } else {
      this.db = new this.SQL.Database();
    }

    this.migrate();
    this.persist();
  }

  async ensureReady(): Promise<void> {
    await this.ready;
  }

  private migrate(): void {
    this.db.run(`
      CREATE TABLE IF NOT EXISTS conversations (
        id TEXT PRIMARY KEY,
        title TEXT NOT NULL,
        createdAt INTEGER NOT NULL,
        updatedAt INTEGER NOT NULL,
        workspacePath TEXT
      );
    `);
    this.db.run(`
      CREATE TABLE IF NOT EXISTS messages (
        id TEXT PRIMARY KEY,
        conversationId TEXT NOT NULL,
        role TEXT NOT NULL,
        content TEXT NOT NULL,
        personaId TEXT,
        createdAt INTEGER NOT NULL
      );
    `);
    this.db.run(`
      CREATE INDEX IF NOT EXISTS idx_messages_conversation
        ON messages(conversationId, createdAt);
    `);

    const info = this.db.exec(`PRAGMA table_info(conversations)`);
    const columns = new Set((info[0]?.values ?? []).map((row) => String(row[1])));
    if (!columns.has('workspacePath')) {
      this.db.run(`ALTER TABLE conversations ADD COLUMN workspacePath TEXT`);
    }
    if (!columns.has('kind')) {
      this.db.run(`ALTER TABLE conversations ADD COLUMN kind TEXT DEFAULT 'chat'`);
    }
  }

  private persist(): void {
    const data = this.db.export();
    fs.writeFileSync(this.filePath, Buffer.from(data));
  }

  private mapConversation(row: {
    id: string;
    title: string;
    createdAt: number;
    updatedAt: number;
    workspacePath?: string | null;
    kind?: string | null;
  }): Conversation {
    const kind: ConversationKind =
      row.kind === 'image'
        ? 'image'
        : row.kind === 'orchestrator'
          ? 'orchestrator'
          : 'chat';
    return {
      id: row.id,
      title: row.title,
      createdAt: row.createdAt,
      updatedAt: row.updatedAt,
      workspacePath: row.workspacePath ?? null,
      kind,
    };
  }

  listConversations(): Conversation[] {
    const result = this.db.exec(
      `SELECT id, title, createdAt, updatedAt, workspacePath, kind
       FROM conversations
       ORDER BY updatedAt DESC`,
    );
    if (!result[0]) return [];
    return result[0].values.map((row) =>
      this.mapConversation({
        id: String(row[0]),
        title: String(row[1]),
        createdAt: Number(row[2]),
        updatedAt: Number(row[3]),
        workspacePath: row[4] == null ? null : String(row[4]),
        kind: row[5] == null ? 'chat' : String(row[5]),
      }),
    );
  }

  createConversation(input: CreateConversationRequest = {}): Conversation {
    const now = Date.now();
    const kind: ConversationKind =
      input.kind === 'image'
        ? 'image'
        : input.kind === 'orchestrator'
          ? 'orchestrator'
          : 'chat';
    const conversation: Conversation = {
      id: randomUUID(),
      title:
        input.title ??
        (kind === 'image'
          ? 'New image'
          : kind === 'orchestrator'
            ? 'New orchestrator'
            : 'New chat'),
      createdAt: now,
      updatedAt: now,
      workspacePath: input.workspacePath ?? null,
      kind,
    };
    this.db.run(
      `INSERT INTO conversations (id, title, createdAt, updatedAt, workspacePath, kind)
       VALUES (?, ?, ?, ?, ?, ?)`,
      [
        conversation.id,
        conversation.title,
        conversation.createdAt,
        conversation.updatedAt,
        conversation.workspacePath,
        conversation.kind,
      ],
    );
    this.persist();
    return conversation;
  }

  renameConversation(id: string, title: string): Conversation | null {
    const updatedAt = Date.now();
    this.db.run(`UPDATE conversations SET title = ?, updatedAt = ? WHERE id = ?`, [
      title,
      updatedAt,
      id,
    ]);
    this.persist();
    return this.getConversation(id);
  }

  deleteConversation(id: string): boolean {
    const before = this.getConversation(id);
    this.db.run(`DELETE FROM messages WHERE conversationId = ?`, [id]);
    this.db.run(`DELETE FROM conversations WHERE id = ?`, [id]);
    this.persist();
    return Boolean(before);
  }

  getConversation(id: string): Conversation | null {
    const stmt = this.db.prepare(
      `SELECT id, title, createdAt, updatedAt, workspacePath, kind FROM conversations WHERE id = ?`,
    );
    stmt.bind([id]);
    if (!stmt.step()) {
      stmt.free();
      return null;
    }
    const row = stmt.getAsObject() as {
      id: string;
      title: string;
      createdAt: number;
      updatedAt: number;
      workspacePath: string | null;
      kind: string | null;
    };
    stmt.free();
    return this.mapConversation(row);
  }

  getMessages(conversationId: string): ChatMessage[] {
    const stmt = this.db.prepare(
      `SELECT id, conversationId, role, content, personaId, createdAt
       FROM messages
       WHERE conversationId = ?
       ORDER BY createdAt ASC`,
    );
    stmt.bind([conversationId]);
    const messages: ChatMessage[] = [];
    while (stmt.step()) {
      const row = stmt.getAsObject() as {
        id: string;
        conversationId: string;
        role: MessageRole;
        content: string;
        personaId: string | null;
        createdAt: number;
      };
      messages.push({
        id: row.id,
        conversationId: row.conversationId,
        role: row.role,
        content: row.content,
        personaId: row.personaId ?? null,
        createdAt: row.createdAt,
      });
    }
    stmt.free();
    return messages;
  }

  addMessage(input: {
    conversationId: string;
    role: MessageRole;
    content: string;
    personaId?: string | null;
    id?: string;
    createdAt?: number;
  }): ChatMessage {
    const message: ChatMessage = {
      id: input.id ?? randomUUID(),
      conversationId: input.conversationId,
      role: input.role,
      content: input.content,
      personaId: input.personaId ?? null,
      createdAt: input.createdAt ?? Date.now(),
    };

    this.db.run(
      `INSERT INTO messages (id, conversationId, role, content, personaId, createdAt)
       VALUES (?, ?, ?, ?, ?, ?)`,
      [
        message.id,
        message.conversationId,
        message.role,
        message.content,
        message.personaId,
        message.createdAt,
      ],
    );

    this.db.run(`UPDATE conversations SET updatedAt = ? WHERE id = ?`, [
      message.createdAt,
      message.conversationId,
    ]);

    if (input.role === 'user') {
      const conversation = this.getConversation(input.conversationId);
      if (
        conversation &&
        (conversation.title === 'New chat' ||
          conversation.title === 'New image' ||
          conversation.title === 'New orchestrator')
      ) {
        const fallback =
          conversation.kind === 'image'
            ? 'New image'
            : conversation.kind === 'orchestrator'
              ? 'New orchestrator'
              : 'New chat';
        const raw =
          conversation.kind === 'image' && input.content.startsWith('Generate image:')
            ? input.content.replace(/^Generate image:\s*/i, '')
            : input.content;
        const title = raw.trim().slice(0, 48) || fallback;
        this.db.run(`UPDATE conversations SET title = ? WHERE id = ?`, [
          title,
          input.conversationId,
        ]);
      }
    }

    this.persist();
    return message;
  }

  updateMessageContent(id: string, content: string): void {
    this.db.run(`UPDATE messages SET content = ? WHERE id = ?`, [content, id]);
    this.persist();
  }

  close(): void {
    if (this.db) {
      this.persist();
      this.db.close();
    }
  }
}
