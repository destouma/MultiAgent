import { useChatStore, type ChatStoreHook } from '../store/chatStore';
import { useSettingsStore } from '../store/settingsStore';

type Props = {
  store?: ChatStoreHook;
};

export function ServerPicker({ store = useChatStore }: Props) {
  const servers = useSettingsStore((state) => state.settings?.servers ?? []);
  const conversations = store((state) => state.conversations);
  const activeConversationId = store((state) => state.activeConversationId);
  const setConversationServer = store((state) => state.setConversationServer);

  if (!servers.length) return null;

  const active = conversations.find((item) => item.id === activeConversationId);
  const value = active?.serverId ?? '';

  return (
    <div className="field server-picker">
      <label htmlFor="server-picker-select">Server</label>
      <select
        id="server-picker-select"
        className="select"
        value={value}
        onChange={(event) => void setConversationServer(event.target.value || null)}
      >
        <option value="">Default</option>
        {servers.map((server) => (
          <option key={server.id} value={server.id}>
            {server.name}
          </option>
        ))}
      </select>
    </div>
  );
}
