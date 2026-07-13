package com.rush.rushaicodemother.core.handler;

/**
 * Project-owned contract for cancelling an active AI generation request.
 */
@FunctionalInterface
public interface GenerationCancellationHandle {

    void cancel();
}
