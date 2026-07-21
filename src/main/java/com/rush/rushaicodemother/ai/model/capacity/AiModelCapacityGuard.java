package com.rush.rushaicodemother.ai.model.capacity;

import dev.langchain4j.model.chat.request.ChatRequest;

import java.time.Duration;

/** Acquires one cluster-wide provider/model capacity lease before a real upstream call. */
public interface AiModelCapacityGuard {

    Lease acquire(String provider,
                  String modelId,
                  int configuredMaxOutputTokens,
                  ChatRequest request);

    /**
     * Acquires capacity for an upstream call with a known wall-clock timeout.
     *
     * <p>The default keeps compatibility with guards that do not manage renewable leases. The
     * distributed implementation uses the timeout to cap heartbeat renewal after a small grace
     * period, so a broken provider client cannot hold cluster capacity forever.</p>
     */
    default Lease acquire(String provider,
                          String modelId,
                          int configuredMaxOutputTokens,
                          ChatRequest request,
                          Duration upstreamTimeout) {
        return acquire(provider, modelId, configuredMaxOutputTokens, request);
    }

    @FunctionalInterface
    interface Lease extends AutoCloseable {
        Lease NOOP = () -> { };

        /** Returns false after the distributed permit is no longer safely owned. */
        default boolean isValid() {
            return true;
        }

        /** Registers one best-effort callback used to cancel an in-flight streaming request. */
        default void onLost(Runnable listener) {
            if (listener == null) {
                throw new IllegalArgumentException("capacity lease loss listener is required");
            }
        }

        @Override
        void close();
    }
}
