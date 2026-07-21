package com.rush.rushaicodemother.orchestration.feedback;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

/**
 * Domain signal emitted after a project-generation task receives explicit user feedback.
 *
 * <p>The service layer publishes this port-level object so downstream AI improvement channels
 * can evolve independently: semantic memory, benchmark mining, analytics, or product coaching
 * can subscribe without coupling feedback persistence to a specific infrastructure adapter.</p>
 */
public record GenerationFeedbackSignal(
        String taskId,
        Long appId,
        Long tenantId,
        Long userId,
        GenerationTaskStatus taskStatus,
        int rating,
        String outcome,
        String comment
) {

    public boolean improvementCandidate() {
        return rating <= 2;
    }
}
