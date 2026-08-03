import { useEffect, useRef, useState } from 'react';
import type { SearchResult } from '../../../shared/types';
import { useChatStore } from '../store/chatStore';
import { useSettingsStore } from '../store/settingsStore';

function kindLabel(kind: SearchResult['kind']): string {
  return kind === 'image' ? 'image' : kind === 'orchestrator' ? 'orchestrator' : 'chat';
}

export function SearchModal() {
  const open = useSettingsStore((state) => state.searchOpen);
  const setSearchOpen = useSettingsStore((state) => state.setSearchOpen);
  const [term, setTerm] = useState('');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [loading, setLoading] = useState(false);
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (open) {
      setTerm('');
      setResults([]);
      requestAnimationFrame(() => inputRef.current?.focus());
    }
  }, [open]);

  useEffect(() => {
    const trimmed = term.trim();
    if (!trimmed) {
      setResults([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    const timer = window.setTimeout(() => {
      void window.api
        .search(trimmed)
        .then((found) => setResults(found))
        .finally(() => setLoading(false));
    }, 200);
    return () => window.clearTimeout(timer);
  }, [term]);

  if (!open) return null;

  const selectResult = (conversationId: string) => {
    setSearchOpen(false);
    void useChatStore.getState().selectConversation(conversationId);
  };

  return (
    <div className="modal-backdrop" onClick={() => setSearchOpen(false)}>
      <div className="modal search-modal" onClick={(event) => event.stopPropagation()}>
        <h2>Search</h2>
        <input
          ref={inputRef}
          className="text-input"
          value={term}
          onChange={(event) => setTerm(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Escape') setSearchOpen(false);
          }}
          placeholder="Search conversation titles and messages…"
        />

        <div className="search-results">
          {loading ? <p className="hint">Searching…</p> : null}
          {!loading && term.trim() && !results.length ? <p className="hint">No matches.</p> : null}
          {results.map((result) => (
            <button
              type="button"
              key={result.conversationId}
              className="search-result"
              onClick={() => selectResult(result.conversationId)}
            >
              <div className="search-result-title">
                {result.title}
                <span className="conversation-folder">{kindLabel(result.kind)}</span>
              </div>
              {result.snippet ? <p className="search-result-snippet">…{result.snippet}…</p> : null}
            </button>
          ))}
        </div>

        <div className="modal-actions">
          <button type="button" className="btn btn-ghost" onClick={() => setSearchOpen(false)}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
