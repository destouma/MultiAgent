import { useEffect, useState } from 'react';
import { useChatStore } from '../store/chatStore';

export function NewChatModal() {
  const open = useChatStore((state) => state.newChatOpen);
  const kind = useChatStore((state) => state.newChatKind);
  const folderPath = useChatStore((state) => state.newChatFolder);
  const setNewChatOpen = useChatStore((state) => state.setNewChatOpen);
  const createConversation = useChatStore((state) => state.createConversation);
  const [title, setTitle] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (open) {
      setTitle('');
    }
  }, [open, kind]);

  if (!open) return null;

  const isImage = kind === 'image';
  const isOrchestrator = kind === 'orchestrator';
  const titleLabel = isImage ? 'New image' : isOrchestrator ? 'New orchestrator' : 'New chat';

  const hint = isImage
    ? 'Create an image session in the main window.'
    : isOrchestrator
      ? 'A supervisor plans which specialists (Researcher, Coder, Critic) to consult, then synthesizes a final answer.'
      : 'Optionally set a title for this chat.';

  const create = async () => {
    setBusy(true);
    try {
      await createConversation(folderPath, kind, title.trim() || null);
      setTitle('');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      className="modal-backdrop"
      onClick={() => {
        if (!busy) setNewChatOpen(false);
      }}
    >
      <div className="modal" onClick={(event) => event.stopPropagation()}>
        <h2>{titleLabel}</h2>
        <p className="hint">{hint}</p>
        {folderPath ? <div className="workspace-path">{folderPath}</div> : null}

        <div className="modal-grid">
          <div className="field">
            <label htmlFor="newChatTitle">Title (optional)</label>
            <input
              id="newChatTitle"
              className="text-input"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder={titleLabel}
              disabled={busy}
              autoFocus
            />
          </div>
        </div>

        <div className="modal-actions">
          <button
            type="button"
            className="btn btn-ghost"
            disabled={busy}
            onClick={() => setNewChatOpen(false)}
          >
            Cancel
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy}
            onClick={() => void create()}
          >
            Create
          </button>
        </div>
      </div>
    </div>
  );
}
