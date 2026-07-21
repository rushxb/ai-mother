package com.rush.rushaicodemother.orchestration.feedback;

import com.rush.rushaicodemother.model.entity.GenerationFeedback;

public interface GenerationFeedbackRepository {

    GenerationFeedback upsert(GenerationFeedback feedback);

    GenerationFeedbackSummary summarizeByAppId(Long appId);
}
