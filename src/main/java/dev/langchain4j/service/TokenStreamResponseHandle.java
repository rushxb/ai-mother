package dev.langchain4j.service;

import dev.langchain4j.model.openai.internal.ResponseHandle;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class TokenStreamResponseHandle extends ResponseHandle {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<ResponseHandle> delegate = new AtomicReference<>();

    @Override
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        ResponseHandle current = delegate.get();
        if (current != null) {
            current.cancel();
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    void updateDelegate(ResponseHandle handle) {
        if (handle == null) {
            return;
        }
        delegate.set(handle);
        if (cancelled.get()) {
            handle.cancel();
        }
    }
}
