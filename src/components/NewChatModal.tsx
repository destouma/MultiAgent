import { useEffect, useState } from 'react';
import { useChatStore } from '../store/chatStore';

export function NewChatModal() {
  const open = useChatStore((state) => state.newChatOpen);
  const kind = useChatStore((state) => state.newChatKind);
  const setNewChatOpen = useChatStore((state) => state.setNewChatOpen);
  const createConversation = useChatStore((state) => state.createConversation);
  const [title, setTitle] = useState('');
  const [folder, setFolder] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (open) {
      setTitle('');
      setFolder(null);
    }
  }, [open, kind]);

  if (!open) return null;

  const isImage = kind === 'image';
  const isOrchestrator = kind === 'orchestrator';
  const titleLabel = isImage ? 'New image' : isOrchestrator ? 'New orchestrator' : 'New chat';
  const onlyLabel = isImage ? 'Image only' : isOrchestrator ? 'Orchestrator only' : 'Chat only';

  const pickFolder = async () => {
    const selected = await window.api.pickFolder();
    if (selected) setFolder(selected);
  };

  const create = async (withFolder: boolean) => {
    setBusy(true);
    try {
      await createConversation(withFolder ? folder : null, kind, title.trim() || null);
      setTitle('');
      setFolder(null);
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
        <p className="hint">
          {isImage
            ? 'Create an image session in the main window. Optionally set a title and bind a folder so generated PNGs can be saved there.'
            : isOrchestrator
              ? 'Create an orchestrator session. A supervisor plans which specialists (Researcher, Coder, Critic) to consult, then synthesizes a final answer. Folder binding is optional for later use.'
              : 'Optionally set a title and bind a folder for this chat. The local model can then list, read, write, and delete files inside that folder only.'}
        </p>

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

        <div className="workspace-picker">
          <div className="workspace-path">{folder ? folder : 'No folder selected'}</div>
          <button type="button" className="btn" onClick={() => void pickFolder()} disabled={busy}>
            Choose folder…
          </button>
          {folder ? (
            <button
              type="button"
              className="btn btn-ghost"
              onClick={() => setFolder(null)}
              disabled={busy}
            >
              Clear
            </button>
          ) : null}
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
          <button type="button" className="btn" disabled={busy} onClick={() => void create(false)}>
            {onlyLabel}
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy || !folder}
            onClick={() => void create(true)}
          >
            Create with folder
          </button>
        </div>
      </div>
    </div>
  );
}
