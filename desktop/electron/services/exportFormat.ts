import type { ChatMessage, Conversation, Persona } from '../../../shared/types';
import { parseImageMessage } from '../../../shared/types';

export type ExportFormat = 'markdown' | 'json';

function roleLabel(message: ChatMessage, personaById: Map<string, Persona>): string {
  if (message.role === 'user') return 'User';
  const persona = message.personaId ? personaById.get(message.personaId) : undefined;
  return persona ? persona.name : 'Assistant';
}

export function formatConversationMarkdown(
  conversation: Conversation,
  messages: ChatMessage[],
  personas: Persona[],
): string {
  const personaById = new Map(personas.map((persona) => [persona.id, persona]));
  const lines = [`# ${conversation.title}`, ''];

  for (const message of messages) {
    const image = message.role === 'assistant' ? parseImageMessage(message.content) : null;
    lines.push(`**${roleLabel(message, personaById)}:**`, '');
    if (image) {
      lines.push(`_Generated image: ${image.prompt}_ (${image.model}, ${image.size})`, '');
    } else {
      lines.push(message.content, '');
    }
  }

  return lines.join('\n');
}

export function formatConversationJson(
  conversation: Conversation,
  messages: ChatMessage[],
): string {
  return JSON.stringify({ conversation, messages }, null, 2);
}

export function slugifyTitle(title: string): string {
  const slug = title
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60);
  return slug || 'conversation';
}
