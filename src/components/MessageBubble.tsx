import { useEffect, useState } from 'react';
import { parseImageMessage, type ChatMessage, type Persona } from '../../shared/types';
import { useChatStore } from '../store/chatStore';
import { MessageContent } from './MessageContent';

type Props = {
  message: ChatMessage;
  persona?: Persona;
  streaming?: boolean;
};

export function MessageBubble({ message, persona, streaming }: Props) {
  const isUser = message.role === 'user';
  const image = !isUser ? parseImageMessage(message.content) : null;
  const activeConversationId = useChatStore((state) => state.activeConversationId);
  const conversations = useChatStore((state) => state.conversations);
  const active = conversations.find((item) => item.id === activeConversationId);
  const [dataUrl, setDataUrl] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);

  useEffect(() => {
    if (!image) return;
    let cancelled = false;
    void window.api
      .getImageDataUrl(image.fileName)
      .then((url) => {
        if (!cancelled) setDataUrl(url);
      })
      .catch(() => {
        if (!cancelled) setDataUrl(null);
      });
    return () => {
      cancelled = true;
    };
    // `image` is re-derived from message.content on every render, so it's a
    // new object each time; depending on the primitive fileName instead
    // avoids refetching on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [image?.fileName]);

  if (image) {
    return (
      <div className="message assistant">
        <div className="message-meta">
          <span className="persona-chip">
            <span className="persona-dot" style={{ background: '#0F766E' }} />
            Image
          </span>
        </div>
        <div className="bubble image-bubble">
          <p className="image-prompt">{image.prompt}</p>
          <p className="image-meta-line">
            {image.model} · {image.size}
            {image.workspaceRelativePath ? ` · saved as ${image.workspaceRelativePath}` : ''}
          </p>
          {dataUrl ? (
            <img className="generated-image" src={dataUrl} alt={image.prompt} />
          ) : (
            <div className="image-missing">Image preview unavailable</div>
          )}
          <div className="image-actions">
            <button
              type="button"
              className="btn"
              disabled={Boolean(busy)}
              onClick={() => {
                setBusy('download');
                void window.api
                  .downloadImage(image.fileName, `${image.prompt.slice(0, 40)}.png`)
                  .then((path) => {
                    setStatus(path ? `Downloaded to ${path}` : 'Download cancelled');
                  })
                  .catch((error: unknown) => {
                    setStatus(error instanceof Error ? error.message : 'Download failed');
                  })
                  .finally(() => setBusy(null));
              }}
            >
              Download
            </button>
            <button
              type="button"
              className="btn btn-primary"
              disabled={Boolean(busy) || !active?.workspacePath}
              title={
                active?.workspacePath
                  ? 'Save into this chat workspace folder'
                  : 'Bind a folder on New chat to enable this'
              }
              onClick={() => {
                if (!activeConversationId) return;
                setBusy('workspace');
                void window.api
                  .saveImageToWorkspace(
                    activeConversationId,
                    image.fileName,
                    image.workspaceRelativePath || `images/${image.fileName}`,
                  )
                  .then((path) => setStatus(`Saved to workspace: ${path}`))
                  .catch((error: unknown) => {
                    setStatus(error instanceof Error ? error.message : 'Save failed');
                  })
                  .finally(() => setBusy(null));
              }}
            >
              Save to folder
            </button>
          </div>
          {status ? <p className="image-status">{status}</p> : null}
        </div>
      </div>
    );
  }

  return (
    <div className={`message ${isUser ? 'user' : 'assistant'}`}>
      {!isUser && (
        <div className="message-meta">
          <span className="persona-chip">
            <span className="persona-dot" style={{ background: persona?.color ?? '#0F766E' }} />
            {persona?.name ?? 'Assistant'}
          </span>
        </div>
      )}
      <div
        className="bubble"
        style={!isUser && persona ? { borderLeft: `3px solid ${persona.color}` } : undefined}
      >
        <MessageContent content={message.content} streaming={streaming} />
      </div>
    </div>
  );
}
