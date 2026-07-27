package com.rush.rushaicodemother.orchestration.feedback;

import com.rush.rushaicodemother.model.entity.GenerationFeedback;

/**
 * 生成反馈持久化仓储。
 */
public interface GenerationFeedbackRepository {

    GenerationFeedback upsert(GenerationFeedback feedback);

    GenerationFeedbackSummary summarizeByAppId(Long appId);
}
