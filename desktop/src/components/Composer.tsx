import { useState, type KeyboardEvent } from 'react';
import { useChatStore } from '../store/chatStore';

export function Composer() {
  const [value, setValue] = useState('');
  const sendMessage = useChatStore((state) => state.sendMessage);
  const cancelStream = useChatStore((state) => state.cancelStream);
  const isStreaming = useChatStore((state) => state.isStreaming);
  const error = useChatStore((state) => state.error);
  const modelStatus = useChatStore((state) => state.modelStatus);

  const onSend = async () => {
    const content = value;
    setValue('');
    await sendMessage(content);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      if (!isStreaming && value.trim()) {
        void onSend();
      }
    }
  };

  return (
    <div className="composer-wrap">
      {error ? <div className="error-banner">{error}</div> : null}
      {modelStatus ? <div className="status-banner">{modelStatus}</div> : null}
      <div className="composer">
        <textarea
          value={value}
          onChange={(event) => setValue(event.target.value)}
          onKeyDown={onKeyDown}
          placeholder="Message the local LLM… (Enter to send, Shift+Enter for newline)"
          disabled={isStreaming}
        />
        <div className="composer-actions">
          {isStreaming ? (
            <button type="button" className="btn btn-danger" onClick={() => void cancelStream()}>
              Stop
            </button>
          ) : (
            <button
              type="button"
              className="btn btn-primary"
              disabled={!value.trim()}
              onClick={() => void onSend()}
            >
              Send
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
