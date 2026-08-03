import { useEffect, useState } from 'react';
import { diffLines } from 'diff';

type DiffLineType = 'add' | 'remove' | 'context';
type DiffLine = { type: DiffLineType; value: string };

type Props = {
  checkpointId: string | null;
  onClose: () => void;
};

// A diffLines() chunk's `value` is line-terminated ("a\nb\n"), so a plain
// split('\n') leaves one trailing "" artifact to drop — except for a final
// chunk that lacks a trailing newline, whose last line must be kept.
function splitChunk(value: string): string[] {
  const lines = value.split('\n');
  if (lines.length && lines[lines.length - 1] === '') {
    lines.pop();
  }
  return lines;
}

export function DiffModal({ checkpointId, onClose }: Props) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [path, setPath] = useState('');
  const [lines, setLines] = useState<DiffLine[]>([]);

  useEffect(() => {
    if (!checkpointId) return;
    setLoading(true);
    setError(null);
    void window.api
      .diffCheckpoint(checkpointId)
      .then((result) => {
        setPath(result.path);
        const parts = diffLines(result.before ?? '', result.after ?? '');
        const computed: DiffLine[] = [];
        for (const part of parts) {
          const type: DiffLineType = part.added ? 'add' : part.removed ? 'remove' : 'context';
          for (const value of splitChunk(part.value)) {
            computed.push({ type, value });
          }
        }
        setLines(computed);
      })
      .catch((err: unknown) => {
        setError(err instanceof Error ? err.message : 'Failed to load diff');
      })
      .finally(() => setLoading(false));
  }, [checkpointId]);

  if (!checkpointId) return null;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal diff-modal" onClick={(event) => event.stopPropagation()}>
        <h2>{path || 'File diff'}</h2>
        {loading ? <p className="hint">Loading…</p> : null}
        {error ? <div className="error-banner">{error}</div> : null}
        {!loading && !error ? (
          <div className="diff-lines">
            {lines.length ? (
              lines.map((line, index) => (
                <div key={index} className={`diff-line diff-line-${line.type}`}>
                  <span className="diff-line-marker">
                    {line.type === 'add' ? '+' : line.type === 'remove' ? '-' : ' '}
                  </span>
                  <span className="diff-line-text">{line.value}</span>
                </div>
              ))
            ) : (
              <p className="hint">No differences.</p>
            )}
          </div>
        ) : null}
        <div className="modal-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
