package dev.langchain4j.model.openai.internal;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides a mechanism to cancel the response after a request has been initiated.
 */
public class ResponseHandle {

    private final Runnable cancelAction;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public ResponseHandle() {
        this(null);
    }

    public ResponseHandle(Runnable cancelAction) {
        this.cancelAction = cancelAction;
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        if (cancelAction != null) {
            cancelAction.run();
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
