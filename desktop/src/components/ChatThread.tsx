import { useEffect, useMemo, useRef } from 'react';
import { useChatStore, type ChatStoreHook } from '../store/chatStore';
import { useSettingsStore } from '../store/settingsStore';
import { MessageBubble } from './MessageBubble';

type Props = {
  store?: ChatStoreHook;
};

export function ChatThread({ store = useChatStore }: Props) {
  const messages = store((state) => state.messages);
  const streamingContent = store((state) => state.streamingContent);
  const streamingMessageId = store((state) => state.streamingMessageId);
  const streamingPersonaId = store((state) => state.streamingPersonaId);
  const isStreaming = store((state) => state.isStreaming);
  const activePersonaId = store((state) => state.activePersonaId);
  const conversations = store((state) => state.conversations);
  const activeConversationId = store((state) => state.activeConversationId);
  const workspaceOps = store((state) => state.workspaceOps);
  const orchestratorStatus = store((state) => state.orchestratorStatus);
  const modelStatus = store((state) => state.modelStatus);
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
          ? 'This chat is bound to a folder. Ask the assistant to inspect or edit files in that workspace.'
          : 'Chat with local LLM models. Open a folder in the sidebar to give a chat read/write access to it.';

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
