package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSnapshot;

import java.time.Instant;

/** Acknowledgement returned after a generation task is accepted. */
public record GenerationTaskSubmissionVO(
        String taskId,
        Long appId,
        String route,
        String status,
        Instant submittedAt,
        Instant deadlineAt
) {
    public static GenerationTaskSubmissionVO from(GenerationTaskSnapshot snapshot) {
        return new GenerationTaskSubmissionVO(
                snapshot.taskId(), snapshot.appId(), snapshot.route(), snapshot.status(),
                snapshot.submittedAt(), snapshot.deadlineAt());
    }
}
