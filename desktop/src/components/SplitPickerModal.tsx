import { useEffect, useState } from 'react';
import type { ConversationKind } from '../../../shared/types';
import { useChatStore, useSecondaryChatStore } from '../store/chatStore';
import { useSplitViewStore } from '../store/splitViewStore';

function kindLabel(kind: ConversationKind): string {
  return kind === 'image' ? 'image' : kind === 'orchestrator' ? 'orchestrator' : 'chat';
}

export function SplitPickerModal() {
  const pickerOpen = useSplitViewStore((state) => state.pickerOpen);
  const folderPath = useSplitViewStore((state) => state.pickerFolderPath);
  const closePicker = useSplitViewStore((state) => state.closePicker);
  const openSplit = useSplitViewStore((state) => state.openSplit);
  const conversations = useChatStore((state) => state.conversations);
  const selectPrimary = useChatStore((state) => state.selectConversation);
  const selectSecondary = useSecondaryChatStore((state) => state.selectConversation);

  const [leftId, setLeftId] = useState('');
  const [rightId, setRightId] = useState('');
  const [busy, setBusy] = useState(false);

  const items = folderPath ? conversations.filter((item) => item.workspacePath === folderPath) : [];

  useEffect(() => {
    if (!pickerOpen) return;
    setLeftId(items[0]?.id ?? '');
    setRightId(items[1]?.id ?? '');
    // Only re-seed when the picker is (re)opened for a folder, not on every conversations change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pickerOpen, folderPath]);

  if (!pickerOpen || !folderPath) return null;

  const canOpen = Boolean(leftId && rightId && leftId !== rightId);

  const onConfirm = async () => {
    if (!canOpen) return;
    setBusy(true);
    try {
      await Promise.all([selectPrimary(leftId), selectSecondary(rightId)]);
      openSplit(folderPath);
      closePicker();
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      className="modal-backdrop"
      onClick={() => {
        if (!busy) closePicker();
      }}
    >
      <div className="modal" onClick={(event) => event.stopPropagation()}>
        <h2>Side by side</h2>
        <p className="hint">Choose two conversations from this folder to view side by side.</p>

        <div className="modal-grid">
          <div className="field">
            <label htmlFor="splitLeft">Left</label>
            <select
              id="splitLeft"
              className="select"
              value={leftId}
              onChange={(event) => setLeftId(event.target.value)}
              disabled={busy}
            >
              {items.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.title} — {kindLabel(item.kind)}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="splitRight">Right</label>
            <select
              id="splitRight"
              className="select"
              value={rightId}
              onChange={(event) => setRightId(event.target.value)}
              disabled={busy}
            >
              {items.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.title} — {kindLabel(item.kind)}
                </option>
              ))}
            </select>
          </div>
        </div>

        {leftId && leftId === rightId ? (
          <p className="hint">Pick two different conversations.</p>
        ) : null}

        <div className="modal-actions">
          <button type="button" className="btn btn-ghost" disabled={busy} onClick={closePicker}>
            Cancel
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={!canOpen || busy}
            onClick={() => void onConfirm()}
          >
            Open side by side
          </button>
        </div>
      </div>
    </div>
  );
}
