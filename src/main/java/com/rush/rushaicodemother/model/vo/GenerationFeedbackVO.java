package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.model.entity.GenerationFeedback;

import java.time.LocalDateTime;

/**
 * 生成反馈接口视图对象。
 */
public record GenerationFeedbackVO(
        Long id,
        String taskId,
        Long appId,
        Long userId,
        Integer rating,
        String outcome,
        String comment,
        LocalDateTime updateTime
) {

    public static GenerationFeedbackVO from(GenerationFeedback feedback) {
        return new GenerationFeedbackVO(
                feedback.getId(),
                feedback.getTaskId(),
                feedback.getAppId(),
                feedback.getUserId(),
                feedback.getRating(),
                feedback.getOutcome(),
                feedback.getComment(),
                feedback.getUpdateTime()
        );
    }
}
