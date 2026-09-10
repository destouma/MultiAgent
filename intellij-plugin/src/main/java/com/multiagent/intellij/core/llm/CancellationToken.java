package com.multiagent.intellij.core.llm;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Java equivalent of the TS clients' AbortSignal - one per in-flight request/conversation. */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable listener : listeners) {
                listener.run();
            }
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void onCancel(Runnable listener) {
        if (isCancelled()) {
            listener.run();
        } else {
            listeners.add(listener);
        }
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new ProviderException(ErrorCode.CANCELLED, "Generation cancelled");
        }
    }
}
