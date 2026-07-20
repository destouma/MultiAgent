import { useChatStore } from '../store/chatStore';

function folderLabel(workspacePath: string | null): string | null {
  if (!workspacePath) return null;
  const parts = workspacePath.replace(/\\/g, '/').split('/');
  return parts[parts.length - 1] || workspacePath;
}

export function ConversationList() {
  const conversations = useChatStore((state) => state.conversations);
  const activeConversationId = useChatStore((state) => state.activeConversationId);
  const selectConversation = useChatStore((state) => state.selectConversation);
  const setNewChatOpen = useChatStore((state) => state.setNewChatOpen);
  const deleteConversation = useChatStore((state) => state.deleteConversation);

  return (
    <aside className="sidebar">
      <div className="brand">
        <div className="brand-mark">MultiAgent</div>
        <div className="brand-sub">Local Lemonade chat</div>
      </div>

      <div className="sidebar-actions">
        <button
          type="button"
          className="btn btn-primary"
          onClick={() => setNewChatOpen(true, 'chat')}
        >
          New chat
        </button>
        <button
          type="button"
          className="btn"
          onClick={() => setNewChatOpen(true, 'image')}
        >
          New image
        </button>
        <button
          type="button"
          className="btn"
          onClick={() => setNewChatOpen(true, 'orchestrator')}
        >
          New orchestrator
        </button>
      </div>

      <div className="conversation-list">
        {conversations.map((conversation) => {
          const folder = folderLabel(conversation.workspacePath);
          const kindLabel =
            conversation.kind === 'image'
              ? 'image'
              : conversation.kind === 'orchestrator'
                ? 'orchestrator'
                : 'chat';
          return (
            <div
              key={conversation.id}
              className={`conversation-item ${
                conversation.id === activeConversationId ? 'active' : ''
              }`}
              role="button"
              tabIndex={0}
              onClick={() => void selectConversation(conversation.id)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  void selectConversation(conversation.id);
                }
              }}
            >
              <div className="conversation-meta">
                <span className="conversation-title">{conversation.title}</span>
                <span className="conversation-folder">
                  {kindLabel}
                  {folder ? ` · ${folder}` : ''}
                </span>
              </div>
              <button
                type="button"
                className="conversation-delete"
                title="Delete conversation"
                onClick={(event) => {
                  event.stopPropagation();
                  void deleteConversation(conversation.id);
                }}
              >
                ×
              </button>
            </div>
          );
        })}
      </div>
    </aside>
  );
}
