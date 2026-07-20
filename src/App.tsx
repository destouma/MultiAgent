import { useEffect } from 'react';
import { ChatThread } from './components/ChatThread';
import { Composer } from './components/Composer';
import { ConnectionBadge } from './components/ConnectionBadge';
import { ConversationList } from './components/ConversationList';
import { ImageStudio } from './components/ImageStudio';
import { ModelPicker } from './components/ModelPicker';
import { NewChatModal } from './components/NewChatModal';
import { PersonaSwitcher } from './components/PersonaSwitcher';
import { SettingsModal } from './components/SettingsModal';
import { useChatStore } from './store/chatStore';
import { useSettingsStore } from './store/settingsStore';

export default function App() {
  const bootstrap = useChatStore((state) => state.bootstrap);
  const appendToken = useChatStore((state) => state.appendToken);
  const appendWorkspaceOp = useChatStore((state) => state.appendWorkspaceOp);
  const setModelStatus = useChatStore((state) => state.setModelStatus);
  const applyOrchestratorStep = useChatStore((state) => state.applyOrchestratorStep);
  const reloadMessages = useChatStore((state) => state.reloadMessages);
  const completeStream = useChatStore((state) => state.completeStream);
  const failStream = useChatStore((state) => state.failStream);
  const conversations = useChatStore((state) => state.conversations);
  const activeConversationId = useChatStore((state) => state.activeConversationId);
  const loadSettings = useSettingsStore((state) => state.load);
  const setSettingsOpen = useSettingsStore((state) => state.setSettingsOpen);

  const active = conversations.find((item) => item.id === activeConversationId) ?? null;
  const isImageSession = active?.kind === 'image';
  const isOrchestratorSession = active?.kind === 'orchestrator';

  useEffect(() => {
    void loadSettings();
    void bootstrap();
  }, [bootstrap, loadSettings]);

  useEffect(() => {
    const offToken = window.api.onChatToken((event) => {
      appendToken(event.messageId, event.delta);
    });
    const offDone = window.api.onChatDone((event) => {
      void completeStream(event.messageId, event.content, event.personaId);
    });
    const offError = window.api.onChatError((event) => {
      if (event.code === 'cancelled') {
        const conversationId = useChatStore.getState().activeConversationId;
        useChatStore.setState({
          isStreaming: false,
          streamingMessageId: null,
          streamingContent: '',
          streamingPersonaId: null,
          orchestratorStatus: null,
        });
        if (conversationId) {
          void useChatStore.getState().selectConversation(conversationId);
        }
        return;
      }
      failStream(event.message);
    });
    const offWorkspace = window.api.onWorkspaceOp((event) => {
      appendWorkspaceOp(event);
    });
    const offModel = window.api.onModelStatus((event) => {
      if (event.phase === 'ready') {
        setModelStatus(null);
      } else {
        setModelStatus(event.message);
      }
    });
    const offOrch = window.api.onOrchestratorStep((event) => {
      applyOrchestratorStep(event);
    });
    const offMessages = window.api.onChatMessagesUpdated((event) => {
      void reloadMessages(event.conversationId);
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
  }, [
    appendToken,
    appendWorkspaceOp,
    applyOrchestratorStep,
    completeStream,
    failStream,
    reloadMessages,
    setModelStatus,
  ]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      void useSettingsStore.getState().refreshHealth();
    }, 15000);
    return () => window.clearInterval(timer);
  }, []);

  return (
    <div className="app-shell">
      <ConversationList />
      <main className="main">
        <header className="topbar">
          <div className="topbar-left">
            {isImageSession ? (
              <div className="brand-inline">Image session</div>
            ) : isOrchestratorSession ? (
              <>
                <div className="brand-inline">Orchestrator</div>
                <ModelPicker />
              </>
            ) : (
              <>
                <PersonaSwitcher />
                <ModelPicker />
              </>
            )}
          </div>
          <div className="topbar-right">
            <ConnectionBadge />
            <button
              type="button"
              className="btn"
              onClick={() => setSettingsOpen(true)}
            >
              Settings
            </button>
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
      <SettingsModal />
      <NewChatModal />
    </div>
  );
}
