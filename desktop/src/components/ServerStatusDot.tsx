import { useEffect, useState } from 'react';
import type { HealthStatus } from '../../../shared/types';

type Props = {
  serverId: string;
};

export function ServerStatusDot({ serverId }: Props) {
  const [health, setHealth] = useState<HealthStatus | null>(null);
  const [checking, setChecking] = useState(false);

  const check = () => {
    setChecking(true);
    void window.api
      .checkHealthForServer(serverId)
      .then(setHealth)
      .catch(() => setHealth({ ok: false, message: 'Check failed' }))
      .finally(() => setChecking(false));
  };

  useEffect(() => {
    check();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverId]);

  const ok = Boolean(health?.ok);
  const label = checking
    ? 'Checking…'
    : health?.ok
      ? `Online${health.latencyMs != null ? ` · ${health.latencyMs}ms` : ''}`
      : (health?.message ?? 'Unknown');

  return (
    <button
      type="button"
      className={`server-status-dot ${checking ? 'checking' : ok ? 'ok' : 'bad'}`}
      onClick={check}
      title={`${label} — click to recheck`}
    />
  );
}
