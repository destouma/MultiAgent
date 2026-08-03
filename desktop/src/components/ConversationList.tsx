import { useEffect, useRef, useState, type MouseEvent } from 'react';
import type { Conversation, ConversationKind } from '../../../shared/types';
import { useChatStore } from '../store/chatStore';
import { useSettingsStore } from '../store/settingsStore';
import { useSplitViewStore } from '../store/splitViewStore';

function folderLabel(folderPath: string): string {
  const parts = folderPath.replace(/\\/g, '/').split('/');
  return parts[parts.length - 1] || folderPath;
}

function kindLabel(kind: ConversationKind): string {
  return kind === 'image' ? 'image' : kind === 'orchestrator' ? 'orchestrator' : 'chat';
}

type FolderContextMenu = {
  folderPath: string;
  x: number;
  y: number;
};

function ConversationRow({ conversation }: { conversation: Conversation }) {
  const activeConversationId = useChatStore((state) => state.activeConversationId);
  const selectConversation = useChatStore((state) => state.selectConversation);
  const deleteConversation = useChatStore((state) => state.deleteConversation);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!menuOpen) return;
    const onPointerDown = (event: globalThis.MouseEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    };
    window.addEventListener('mousedown', onPointerDown);
    return () => window.removeEventListener('mousedown', onPointerDown);
  }, [menuOpen]);

  const exportAs = (format: 'markdown' | 'json') => {
    setMenuOpen(false);
    void window.api.exportConversation(conversation.id, format);
  };

  return (
    <div
      className={`conversation-item ${conversation.id === activeConversationId ? 'active' : ''}`}
      role="button"
      tabIndex={0}
      onClick={() => void selectConversation(conversation.id)}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          void selectConversation(conversation.id);
        }
      }}
    >
      <div className="conversation-meta">
        <span className="conversation-title">{conversation.title}</span>
        <span className="conversation-folder">{kindLabel(conversation.kind)}</span>
      </div>
      <div className="conversation-item-actions" ref={menuRef}>
        <button
          type="button"
          className="conversation-menu-btn"
          title="Export conversation"
          onClick={(event) => {
            event.stopPropagation();
            setMenuOpen((open) => !open);
          }}
        >
          ⋯
        </button>
        {menuOpen ? (
          <div className="conversation-menu-pop" onClick={(event) => event.stopPropagation()}>
            <button type="button" onClick={() => exportAs('markdown')}>
              Export as Markdown
            </button>
            <button type="button" onClick={() => exportAs('json')}>
              Export as JSON
            </button>
          </div>
        ) : null}
        <button
          type="button"
          className="conversation-delete"
          title="Delete conversation"
          onClick={(event) => {
            event.stopPropagation();
            void deleteConversation(conversation.id);
          }}
        >
          ×
        </button>
      </div>
    </div>
  );
}

export function ConversationList() {
  const conversations = useChatStore((state) => state.conversations);
  const folders = useChatStore((state) => state.folders);
  const setNewChatOpen = useChatStore((state) => state.setNewChatOpen);
  const openFolder = useChatStore((state) => state.openFolder);
  const removeFolder = useChatStore((state) => state.removeFolder);
  const openSplitPicker = useSplitViewStore((state) => state.openPicker);
  const setSearchOpen = useSettingsStore((state) => state.setSearchOpen);
  const [contextMenu, setContextMenu] = useState<FolderContextMenu | null>(null);

  useEffect(() => {
    if (!contextMenu) return;
    const close = () => setContextMenu(null);
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') close();
    };
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('resize', close);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('resize', close);
    };
  }, [contextMenu]);

  const folderPaths = new Set(folders.map((folder) => folder.path));
  const ungrouped = conversations.filter(
    (conversation) => !conversation.workspacePath || !folderPaths.has(conversation.workspacePath),
  );

  const openContextMenu = (folderPath: string, event: MouseEvent) => {
    event.preventDefault();
    setContextMenu({ folderPath, x: event.clientX, y: event.clientY });
  };

  const createInFolder = (kind: ConversationKind) => {
    if (!contextMenu) return;
    setNewChatOpen(true, kind, contextMenu.folderPath);
    setContextMenu(null);
  };

  const confirmRemoveFolder = (folderPath: string) => {
    const label = folderLabel(folderPath);
    const ok = window.confirm(
      `Remove folder "${label}" from the sidebar?\n\nChats in this folder stay, but the workspace binding is cleared.`,
    );
    if (!ok) return;
    void removeFolder(folderPath);
    setContextMenu(null);
  };

  const openSideBySide = () => {
    if (!contextMenu) return;
    openSplitPicker(contextMenu.folderPath);
    setContextMenu(null);
  };

  const contextMenuFolderCount = contextMenu
    ? conversations.filter((item) => item.workspacePath === contextMenu.folderPath).length
    : 0;

  return (
    <aside className="sidebar">
      <div className="brand">
        <div className="brand-mark">MultiAgent</div>
        <div className="brand-sub">Local LLM chat</div>
      </div>

      <div className="sidebar-actions">
        <button type="button" className="btn" onClick={() => setSearchOpen(true)}>
          Search
        </button>
        <button type="button" className="btn" onClick={() => setNewChatOpen(true, 'chat')}>
          New chat
        </button>
        <button type="button" className="btn" onClick={() => setNewChatOpen(true, 'image')}>
          New image
        </button>
        <button type="button" className="btn" onClick={() => setNewChatOpen(true, 'orchestrator')}>
          New orchestrator
        </button>
        <button type="button" className="btn" onClick={() => void openFolder()}>
          Open folder
        </button>
      </div>

      <div className="conversation-list">
        {folders.map((folder) => {
          const items = conversations.filter((item) => item.workspacePath === folder.path);
          return (
            <div key={folder.path} className="folder-group">
              <div
                className="folder-header"
                title={folder.path}
                onContextMenu={(event) => openContextMenu(folder.path, event)}
              >
                <span className="folder-icon">📁</span>
                <span className="folder-name">{folderLabel(folder.path)}</span>
                <button
                  type="button"
                  className="folder-remove"
                  title="Remove folder"
                  onClick={(event) => {
                    event.stopPropagation();
                    confirmRemoveFolder(folder.path);
                  }}
                >
                  ×
                </button>
              </div>
              {items.length ? (
                <div className="folder-items">
                  {items.map((conversation) => (
                    <ConversationRow key={conversation.id} conversation={conversation} />
                  ))}
                </div>
              ) : (
                <p className="folder-empty-hint">
                  Right-click to start a chat, image, or orchestrator session here.
                </p>
              )}
            </div>
          );
        })}

        {ungrouped.map((conversation) => (
          <ConversationRow key={conversation.id} conversation={conversation} />
        ))}
      </div>

      {contextMenu ? (
        <>
          <div className="context-menu-backdrop" onClick={() => setContextMenu(null)} />
          <div
            className="context-menu"
            style={{ left: contextMenu.x, top: contextMenu.y }}
            onClick={(event) => event.stopPropagation()}
          >
            <button type="button" onClick={() => createInFolder('chat')}>
              New chat
            </button>
            <button type="button" onClick={() => createInFolder('image')}>
              New image
            </button>
            <button type="button" onClick={() => createInFolder('orchestrator')}>
              New orchestrator
            </button>
            {contextMenuFolderCount >= 2 ? (
              <button type="button" onClick={openSideBySide}>
                Side by side
              </button>
            ) : null}
            <button
              type="button"
              className="context-menu-danger"
              onClick={() => confirmRemoveFolder(contextMenu.folderPath)}
            >
              Remove folder
            </button>
          </div>
        </>
      ) : null}
    </aside>
  );
}
