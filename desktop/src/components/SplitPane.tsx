import { ChatThread } from './ChatThread';
import { Composer } from './Composer';
import { ImageStudio } from './ImageStudio';
import { ModelPicker } from './ModelPicker';
import { PersonaSwitcher } from './PersonaSwitcher';
import { useSecondaryChatStore } from '../store/chatStore';

export function SplitPane() {
  const conversations = useSecondaryChatStore((state) => state.conversations);
  const activeConversationId = useSecondaryChatStore((state) => state.activeConversationId);

  const active = conversations.find((item) => item.id === activeConversationId) ?? null;
  const isImageSession = active?.kind === 'image';
  const isOrchestratorSession = active?.kind === 'orchestrator';

  return (
    <div className="split-pane">
      <header className="topbar">
        <div className="topbar-left">
          {isImageSession ? (
            <>
              <div className="brand-inline">Image session</div>
              <ModelPicker kind="image" store={useSecondaryChatStore} />
            </>
          ) : isOrchestratorSession ? (
            <>
              <div className="brand-inline">Orchestrator</div>
              <ModelPicker kind="chat" store={useSecondaryChatStore} />
            </>
          ) : (
            <>
              <PersonaSwitcher store={useSecondaryChatStore} />
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
