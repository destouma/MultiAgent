import { ChatThread } from './ChatThread';
import { Composer } from './Composer';
import { ImageStudio } from './ImageStudio';
import { ModelPicker } from './ModelPicker';
import { PersonaSwitcher } from './PersonaSwitcher';
import { ServerPicker } from './ServerPicker';
import { useSecondaryChatStore } from '../store/chatStore';

export function SplitPane() {
  const conversations = useSecondaryChatStore((state) => state.conversations);
  const activeConversationId = useSecondaryChatStore((state) => state.activeConversationId);
  const isBusy = useSecondaryChatStore((state) => state.isStreaming || state.generatingImage);

  const active = conversations.find((item) => item.id === activeConversationId) ?? null;
  const isImageSession = active?.kind === 'image';
  const isOrchestratorSession = active?.kind === 'orchestrator';

  return (
    <div className={`split-pane ${isBusy ? 'busy' : ''}`}>
      <header className="topbar">
        <div className="topbar-left">
          {isImageSession ? (
            <>
              <div className="brand-inline">Image session</div>
              <ServerPicker store={useSecondaryChatStore} />
              <ModelPicker kind="image" store={useSecondaryChatStore} />
            </>
          ) : isOrchestratorSession ? (
            <>
              <div className="brand-inline">Orchestrator</div>
              <ServerPicker store={useSecondaryChatStore} />
              <ModelPicker kind="chat" store={useSecondaryChatStore} />
            </>
          ) : (
            <>
              <PersonaSwitcher store={useSecondaryChatStore} />
              <ServerPicker store={useSecondaryChatStore} />
              <ModelPicker kind="chat" store={useSecondaryChatStore} />
            </>
          )}
        </div>
      </header>
      {isImageSession ? (
        <ImageStudio store={useSecondaryChatStore} />
      ) : (
        <>
          <ChatThread store={useSecondaryChatStore} />
          <Composer store={useSecondaryChatStore} />
        </>
      )}
    </div>
  );
}
