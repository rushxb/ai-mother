package com.rush.rushaicodemother.orchestration.feedback;

/**
 * 生成反馈命令的不可变数据载体。
 */
public record GenerationFeedbackCommand(
        String taskId,
        int rating,
        String outcome,
        String comment
) {
}
