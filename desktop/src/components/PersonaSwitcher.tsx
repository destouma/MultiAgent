import { useChatStore, type ChatStoreHook } from '../store/chatStore';
import { useSettingsStore } from '../store/settingsStore';

type Props = {
  store?: ChatStoreHook;
};

export function PersonaSwitcher({ store = useChatStore }: Props) {
  const personas = useSettingsStore((state) => state.personas).filter(
    (persona) => persona.id !== 'orchestrator',
  );
  const activePersonaId = store((state) => state.activePersonaId);
  const setActivePersona = store((state) => state.setActivePersona);
  const active = personas.find((persona) => persona.id === activePersonaId);

  return (
    <div className="field">
      <label htmlFor="persona">Persona</label>
      <select
        id="persona"
        className="select"
        value={activePersonaId}
        onChange={(event) => setActivePersona(event.target.value)}
        style={active ? { borderColor: active.color } : undefined}
      >
        {personas.map((persona) => (
          <option key={persona.id} value={persona.id}>
            {persona.name}
          </option>
        ))}
      </select>
    </div>
  );
}
