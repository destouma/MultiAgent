import { useEffect } from 'react';
import { ChatThread } from './components/ChatThread';
import { Composer } from './components/Composer';
import { ConnectionBadge } from './components/ConnectionBadge';
import { ConversationList } from './components/ConversationList';
import { ImageStudio } from './components/ImageStudio';
import { ModelPicker } from './components/ModelPicker';
import { ModelsModal } from './components/ModelsModal';
import { NewChatModal } from './components/NewChatModal';
import { PersonaSwitcher } from './components/PersonaSwitcher';
import { SettingsModal } from './components/SettingsModal';
import { SplitPane } from './components/SplitPane';
import { SplitPickerModal } from './components/SplitPickerModal';
import { useChatStore, useSecondaryChatStore, type ChatStoreHook } from './store/chatStore';
import { useSettingsStore } from './store/settingsStore';
import { useSplitViewStore } from './store/splitViewStore';

const PANE_STORES: ChatStoreHook[] = [useChatStore, useSecondaryChatStore];

export default function App() {
  const bootstrap = useChatStore((state) => state.bootstrap);
  const conversations = useChatStore((state) => state.conversations);
  const activeConversationId = useChatStore((state) => state.activeConversationId);
  const isBusy = useChatStore((state) => state.isStreaming || state.generatingImage);
  const splitOpen = useSplitViewStore((state) => state.open);
  const loadSettings = useSettingsStore((state) => state.load);
  const setSettingsOpen = useSettingsStore((state) => state.setSettingsOpen);
  const setModelsOpen = useSettingsStore((state) => state.setModelsOpen);

  const closeSplit = useSplitViewStore((state) => state.closeSplit);

  const active = conversations.find((item) => item.id === activeConversationId) ?? null;
  const isImageSession = active?.kind === 'image';
  const isOrchestratorSession = active?.kind === 'orchestrator';

  useEffect(() => {
    void loadSettings();
    void bootstrap();
  }, [bootstrap, loadSettings]);

  useEffect(() => {
    const offToken = window.api.onChatToken((event) => {
      for (const store of PANE_STORES) {
        store.getState().appendToken(event.conversationId, event.messageId, event.delta);
      }
    });
    const offDone = window.api.onChatDone((event) => {
      for (const store of PANE_STORES) {
        void store
          .getState()
          .completeStream(event.conversationId, event.messageId, event.content, event.personaId);
      }
    });
    const offError = window.api.onChatError((event) => {
      if (event.code === 'cancelled') {
        for (const store of PANE_STORES) {
          store.getState().handleCancelled(event.conversationId);
        }
        return;
      }
      for (const store of PANE_STORES) {
        store.getState().failStream(event.conversationId, event.message);
      }
    });
    const offWorkspace = window.api.onWorkspaceOp((event) => {
      for (const store of PANE_STORES) {
        store.getState().appendWorkspaceOp(event);
      }
    });
    const offModel = window.api.onModelStatus((event) => {
      const message = event.phase === 'ready' ? null : event.message;
      for (const store of PANE_STORES) {
        store.getState().setModelStatus(message);
      }
    });
    const offOrch = window.api.onOrchestratorStep((event) => {
      for (const store of PANE_STORES) {
        store.getState().applyOrchestratorStep(event);
      }
    });
    const offMessages = window.api.onChatMessagesUpdated((event) => {
      for (const store of PANE_STORES) {
        void store.getState().reloadMessages(event.conversationId);
      }
    });

    return () => {
      offToken();
      offDone();
      offError();
      offWorkspace();
      offModel();
      offOrch();
      offMessages();
    };
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => {
      void useSettingsStore.getState().refreshHealth();
    }, 15000);
    return () => window.clearInterval(timer);
  }, []);

  return (
    <div className="app-shell">
      <ConversationList />
      <div className="content-column">
        <header className="topbar global-topbar">
          <ConnectionBadge />
          <button type="button" className="btn" onClick={() => setModelsOpen(true)}>
            Models
          </button>
          <button type="button" className="btn" onClick={() => setSettingsOpen(true)}>
            Settings
          </button>
          {splitOpen ? (
            <button type="button" className="btn" title="Close side by side" onClick={closeSplit}>
              × Close split
            </button>
          ) : null}
        </header>
        <div className={`main-area ${splitOpen ? 'split' : ''}`}>
          <main className={`main ${isBusy ? 'busy' : ''}`}>
            <header className="topbar">
              <div className="topbar-left">
                {isImageSession ? (
                  <>
                    <div className="brand-inline">Image session</div>
                    <ModelPicker kind="image" />
                  </>
                ) : isOrchestratorSession ? (
                  <>
                    <div className="brand-inline">Orchestrator</div>
                    <ModelPicker kind="chat" />
                  </>
                ) : (
                  <>
                    <PersonaSwitcher />
                    <ModelPicker kind="chat" />
                  </>
                )}
              </div>
            </header>
            {isImageSession ? (
              <ImageStudio />
            ) : (
              <>
                <ChatThread />
                <Composer />
              </>
            )}
          </main>
          {splitOpen ? <SplitPane /> : null}
        </div>
      </div>
      <SettingsModal />
      <ModelsModal />
      <NewChatModal />
      <SplitPickerModal />
    </div>
  );
}
