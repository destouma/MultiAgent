import { useEffect, useId, useMemo, useRef, useState } from 'react';
import type { ModelInfo } from '../../../shared/types';

type Props = {
  id?: string;
  label?: string;
  value: string;
  models: ModelInfo[];
  loadedModels: string[];
  /** False when the server doesn't report load status at all (e.g. NoLlama); shows a neutral dot instead of implying "not loaded". */
  loadStatusSupported?: boolean;
  disabled?: boolean;
  onChange: (modelId: string) => void;
  onOpen?: () => void;
  className?: string;
};

export function ModelSelect({
  id,
  label = 'Model',
  value,
  models,
  loadedModels,
  loadStatusSupported = true,
  disabled = false,
  onChange,
  onOpen,
  className,
}: Props) {
  const generatedId = useId();
  const fieldId = id ?? generatedId;
  const rootRef = useRef<HTMLDivElement | null>(null);
  const [open, setOpen] = useState(false);

  const loadedSet = useMemo(
    () => new Set(loadedModels.map((name) => name.toLowerCase())),
    [loadedModels],
  );

  const selected = models.find((model) => model.id === value) ?? null;
  const selectedLoaded = selected ? loadedSet.has(selected.id.toLowerCase()) : false;

  const statusDotClass = (loaded: boolean) =>
    loadStatusSupported ? (loaded ? 'loaded' : 'unloaded') : 'unknown';
  const statusLabel = (loaded: boolean) =>
    loadStatusSupported
      ? loaded
        ? 'Loaded'
        : 'Not loaded'
      : 'Load status not reported by this server';

  useEffect(() => {
    if (!open) return;
    onOpen?.();
    const onPointerDown = (event: MouseEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    window.addEventListener('mousedown', onPointerDown);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('mousedown', onPointerDown);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [open, onOpen]);

  return (
    <div className={`field model-select ${className ?? ''}`} ref={rootRef}>
      {label ? <label htmlFor={fieldId}>{label}</label> : null}
      <button
        type="button"
        id={fieldId}
        className="select model-select-trigger"
        disabled={disabled || !models.length}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        {models.length ? (
          <>
            <span
              className={`model-status-dot ${statusDotClass(selectedLoaded)}`}
              title={statusLabel(selectedLoaded)}
              aria-hidden
            />
            <span className="model-select-value">{selected?.id || value || 'Select model'}</span>
          </>
        ) : (
          <span className="model-select-value">No models</span>
        )}
        <span className="model-select-caret" aria-hidden>
          ▾
        </span>
      </button>

      {open && models.length ? (
        <div className="model-select-menu" role="listbox" aria-labelledby={fieldId}>
          {models.map((model) => {
            const loaded = loadedSet.has(model.id.toLowerCase());
            const isActive = model.id === value;
            return (
              <button
                key={model.id}
                type="button"
                role="option"
                aria-selected={isActive}
                className={`model-select-option ${isActive ? 'active' : ''}`}
                onClick={() => {
                  onChange(model.id);
                  setOpen(false);
                }}
              >
                <span
                  className={`model-status-dot ${statusDotClass(loaded)}`}
                  title={statusLabel(loaded)}
                  aria-label={statusLabel(loaded)}
                />
                <span className="model-select-option-name">{model.id}</span>
              </button>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}
