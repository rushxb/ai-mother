package com.rush.rushaicodemother.infrastructure.persistence.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.mapper.PromptCanaryAssessmentMapper;
import com.rush.rushaicodemother.model.entity.PromptCanaryAssessmentEntity;
import com.rush.rushaicodemother.service.prompt.canary.PromptCanaryAssessment;
import com.rush.rushaicodemother.service.prompt.canary.PromptCanaryAssessmentStore;
import com.rush.rushaicodemother.service.prompt.canary.PromptCanaryEvaluationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** Prompt 灰度评估的只追加 MyBatis 存储。 */
@Repository
@RequiredArgsConstructor
public class MyBatisPromptCanaryAssessmentStore implements PromptCanaryAssessmentStore {

    private final PromptCanaryAssessmentMapper mapper;
    private final ObjectMapper objectMapper;
    private final ZoneId databaseZone = ZoneId.systemDefault();

    @Override
    public void save(PromptCanaryAssessment assessment) {
        PromptCanaryEvaluationRequest request = assessment.request();
        PromptCanaryAssessmentEntity entity = PromptCanaryAssessmentEntity.builder()
                .assessmentId(assessment.assessmentId())
                .promptKey(request.promptKey())
                .releaseRevision(request.releaseRevision())
                .bundleRevision(request.bundleRevision())
                .bundleId(request.bundleId())
                .stableVersion(request.stableVersion())
                .stableContentHash(request.stableContentHash())
                .canaryVersion(request.canaryVersion())
                .canaryContentHash(request.canaryContentHash())
                .windowStart(toLocal(request.windowStart()))
                .windowEnd(toLocal(request.windowEnd()))
                .decision(assessment.decision().name())
                .stableTaskCount(assessment.stableTaskCount())
                .canaryTaskCount(assessment.canaryTaskCount())
                .ambiguousTaskCount(assessment.ambiguousTaskCount())
                .invalidAttributionTaskCount(assessment.invalidAttributionTaskCount())
                .violationsJson(toJson(assessment.violations()))
                .evidenceJson(assessment.evidenceJson())
                .evidenceHash(assessment.evidenceHash())
                .evaluatedAt(toLocal(assessment.evaluatedAt()))
                .createTime(toLocal(assessment.evaluatedAt()))
                .build();
        if (mapper.insert(entity) != 1) {
            throw new IllegalStateException("Prompt 灰度评估证据写入失败");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Prompt 灰度违规项无法序列化", exception);
        }
    }

    private LocalDateTime toLocal(java.time.Instant value) {
        return LocalDateTime.ofInstant(value, databaseZone);
    }
}
