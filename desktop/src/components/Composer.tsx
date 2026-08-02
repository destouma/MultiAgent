import { useState, type KeyboardEvent } from 'react';
import { useChatStore, useSecondaryChatStore, type ChatStoreHook } from '../store/chatStore';

type Props = {
  store?: ChatStoreHook;
};

export function Composer({ store = useChatStore }: Props) {
  const [value, setValue] = useState('');
  const sendMessage = store((state) => state.sendMessage);
  const cancelStream = store((state) => state.cancelStream);
  const isStreaming = store((state) => state.isStreaming);
  const error = store((state) => state.error);
  const modelStatus = store((state) => state.modelStatus);

  const otherStore = store === useChatStore ? useSecondaryChatStore : useChatStore;
  const otherBusy = otherStore((state) => state.isStreaming || state.generatingImage);

  const onSend = async () => {
    const content = value;
    setValue('');
    await sendMessage(content);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      if (!isStreaming && !otherBusy && value.trim()) {
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
          disabled={isStreaming || otherBusy}
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
              disabled={!value.trim() || otherBusy}
              title={
                otherBusy ? 'Only one response can generate at a time across split view' : undefined
              }
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
