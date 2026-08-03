import { useMemo } from 'react';
import { contextUsageLevel, estimateTokens } from '../../../shared/tokenEstimate';
import { useChatStore, type ChatStoreHook } from '../store/chatStore';
import { useSettingsStore } from '../store/settingsStore';

type Props = {
  store?: ChatStoreHook;
};

export function ContextUsage({ store = useChatStore }: Props) {
  const conversations = store((state) => state.conversations);
  const activeConversationId = store((state) => state.activeConversationId);
  const activePersonaId = store((state) => state.activePersonaId);
  const messages = store((state) => state.messages);
  const personas = useSettingsStore((state) => state.personas);
  const settings = useSettingsStore((state) => state.settings);

  const active = conversations.find((item) => item.id === activeConversationId);
  const isOrchestrator = active?.kind === 'orchestrator';
  const personaId = isOrchestrator ? 'orchestrator' : activePersonaId;
  const persona = personas.find((item) => item.id === personaId);
  const profile = active?.serverId
    ? settings?.servers.find((server) => server.id === active.serverId)
    : null;
  const maxHistory = profile?.maxHistory ?? settings?.maxHistory ?? 40;

  const { tokens, level, count } = useMemo(() => {
    const capped = messages.slice(-Math.max(1, maxHistory));
    const estimated = estimateTokens([
      persona?.systemPrompt ?? '',
      ...capped.map((message) => message.content),
    ]);
    return { tokens: estimated, level: contextUsageLevel(estimated), count: capped.length };
  }, [messages, maxHistory, persona?.systemPrompt]);

  if (!active || active.kind === 'image') return null;

  return (
    <div
      className={`context-usage context-usage-${level}`}
      title="Approximate size of what will be sent as context for the next message (~4 characters per token; not exact)"
    >
      <span className="context-usage-dot" />~{tokens.toLocaleString()} tokens · last {count} message
      {count === 1 ? '' : 's'}
    </div>
  );
}
