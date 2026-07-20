import { useEffect, useMemo, useRef } from 'react';
import { useChatStore } from '../store/chatStore';
import { useSettingsStore } from '../store/settingsStore';
import { MessageBubble } from './MessageBubble';

export function ChatThread() {
  const messages = useChatStore((state) => state.messages);
  const streamingContent = useChatStore((state) => state.streamingContent);
  const streamingMessageId = useChatStore((state) => state.streamingMessageId);
  const streamingPersonaId = useChatStore((state) => state.streamingPersonaId);
  const isStreaming = useChatStore((state) => state.isStreaming);
  const activePersonaId = useChatStore((state) => state.activePersonaId);
  const conversations = useChatStore((state) => state.conversations);
  const activeConversationId = useChatStore((state) => state.activeConversationId);
  const workspaceOps = useChatStore((state) => state.workspaceOps);
  const orchestratorStatus = useChatStore((state) => state.orchestratorStatus);
  const modelStatus = useChatStore((state) => state.modelStatus);
  const personas = useSettingsStore((state) => state.personas);
  const bottomRef = useRef<HTMLDivElement | null>(null);

  const activeConversation = useMemo(
    () => conversations.find((item) => item.id === activeConversationId) ?? null,
    [conversations, activeConversationId],
  );

  const isOrchestrator = activeConversation?.kind === 'orchestrator';

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, streamingContent, workspaceOps, orchestratorStatus]);

  const personaById = new Map(personas.map((persona) => [persona.id, persona]));
  const bubblePersonaId = streamingPersonaId ?? (isOrchestrator ? 'orchestrator' : activePersonaId);
  const streamingPersona = personaById.get(bubblePersonaId);

  const emptyTitle =
    activeConversation?.kind === 'image'
      ? 'Image session'
      : isOrchestrator
        ? 'Orchestrator'
        : 'MultiAgent';

  const emptyCopy =
    activeConversation?.kind === 'image'
      ? 'Describe an image below and click Generate. Results appear here with Download / Save to folder.'
      : isOrchestrator
        ? 'Ask a question. The orchestrator will plan, consult specialists (Researcher, Coder, Critic), and synthesize a final answer.'
        : activeConversation?.workspacePath
          ? 'This chat is bound to a folder. Ask Lemonade to inspect or edit files in that workspace.'
          : 'Chat with local Lemonade models. Use New chat to optionally bind a workspace folder.';

  const placeholderContent =
    streamingContent ||
    orchestratorStatus ||
    (workspaceOps.length ? 'Working in workspace…' : modelStatus || '…');

  return (
    <div className="thread">
      {activeConversation?.workspacePath ? (
        <div className="workspace-banner" title={activeConversation.workspacePath}>
          Workspace: <code>{activeConversation.workspacePath}</code>
          <span className="workspace-banner-note">read / write enabled</span>
        </div>
      ) : null}

      {isOrchestrator && isStreaming && orchestratorStatus ? (
        <div className="orchestrator-banner">{orchestratorStatus}</div>
      ) : null}

      {!messages.length && !isStreaming ? (
        <div className="thread-empty">
          <h2>{emptyTitle}</h2>
          <p>{emptyCopy}</p>
        </div>
      ) : (
        <div className="messages">
          {messages.map((message) => (
            <MessageBubble
              key={message.id}
              message={message}
              persona={message.personaId ? personaById.get(message.personaId) : undefined}
            />
          ))}
          {isStreaming && workspaceOps.length ? (
            <div className="workspace-ops">
              {workspaceOps.map((op, index) => (
                <div
                  key={`${op.messageId}-${op.op}-${op.path}-${index}`}
                  className={`workspace-op ${op.status}`}
                >
                  <strong>{op.op}</strong> {op.path}
                  {op.detail ? <span> — {op.detail}</span> : null}
                </div>
              ))}
            </div>
          ) : null}
          {isStreaming && streamingMessageId ? (
            <MessageBubble
              message={{
                id: streamingMessageId,
                conversationId: '',
                role: 'assistant',
                content: placeholderContent,
                personaId: bubblePersonaId,
                createdAt: Date.now(),
              }}
              persona={streamingPersona}
              streaming
            />
          ) : null}
          <div ref={bottomRef} />
        </div>
      )}
    </div>
  );
}
