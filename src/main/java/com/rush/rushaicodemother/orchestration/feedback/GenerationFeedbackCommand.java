package com.rush.rushaicodemother.orchestration.feedback;

public record GenerationFeedbackCommand(
        String taskId,
        int rating,
        String outcome,
        String comment
) {
}
