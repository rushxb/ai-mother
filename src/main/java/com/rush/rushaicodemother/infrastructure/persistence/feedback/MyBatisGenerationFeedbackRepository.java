package com.rush.rushaicodemother.infrastructure.persistence.feedback;

import com.rush.rushaicodemother.mapper.GenerationFeedbackMapper;
import com.rush.rushaicodemother.model.entity.GenerationFeedback;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackRepository;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class MyBatisGenerationFeedbackRepository implements GenerationFeedbackRepository {

    private final GenerationFeedbackMapper mapper;

    @Override
    @Transactional
    public GenerationFeedback upsert(GenerationFeedback feedback) {
        mapper.upsert(feedback);
        return mapper.selectByTaskIdAndUserId(feedback.getTaskId(), feedback.getUserId());
    }

    @Override
    public GenerationFeedbackSummary summarizeByAppId(Long appId) {
        if (appId == null || appId <= 0) {
            return GenerationFeedbackSummary.empty();
        }
        GenerationFeedbackSummary summary = mapper.summarizeByAppId(appId);
        return summary == null ? GenerationFeedbackSummary.empty() : summary;
    }
}
