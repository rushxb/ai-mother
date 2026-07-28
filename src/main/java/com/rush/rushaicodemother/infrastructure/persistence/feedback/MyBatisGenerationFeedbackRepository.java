package com.rush.rushaicodemother.infrastructure.persistence.feedback;

import com.rush.rushaicodemother.mapper.GenerationFeedbackMapper;
import com.rush.rushaicodemother.model.entity.GenerationFeedback;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackRepository;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * MyBatis生成反馈持久化仓储。
 */
@Repository
@RequiredArgsConstructor
public class MyBatisGenerationFeedbackRepository implements GenerationFeedbackRepository {

    private final GenerationFeedbackMapper mapper;

    /**
 * 新增或更新{@code My}{@code Batis}生成反馈。
 *
 * @param feedback 反馈
 * @return {@code My}{@code Batis}生成反馈
 */
    @Override
    @Transactional
    public GenerationFeedback upsert(GenerationFeedback feedback) {
        mapper.upsert(feedback);
        return mapper.selectByTaskIdAndUserId(feedback.getTaskId(), feedback.getUserId());
    }

    /**
 * 计算{@code marize}按应用编号的汇总值。
 *
 * @param appId 应用编号
 * @return {@code marize}按应用编号
 */
    @Override
    public GenerationFeedbackSummary summarizeByAppId(Long appId) {
        if (appId == null || appId <= 0) {
            return GenerationFeedbackSummary.empty();
        }
        GenerationFeedbackSummary summary = mapper.summarizeByAppId(appId);
        return summary == null ? GenerationFeedbackSummary.empty() : summary;
    }
}
