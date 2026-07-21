package com.rush.rushaicodemother.orchestration.feedback;

/**
 * Publishes user feedback as an AI-improvement signal.
 *
 * <p>Implementations must be best-effort: a downstream memory or analytics outage must not make
 * the already-persisted feedback submission fail.</p>
 */
public interface GenerationFeedbackSignalPublisher {

    void publish(GenerationFeedbackSignal signal);
}
