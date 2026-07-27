package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSnapshot;

import java.time.Instant;

/** 接受生成任务后返回确认。 */
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
