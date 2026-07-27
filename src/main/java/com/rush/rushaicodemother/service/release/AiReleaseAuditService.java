package com.rush.rushaicodemother.service.release;

import com.rush.rushaicodemother.mapper.AiReleaseAuditMapper;
import com.rush.rushaicodemother.model.entity.AiReleaseAuditEntity;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** 仅附加审核将应用的 AI 版本变更与其确切的基准证据联系起来。 */
@Service
@RequiredArgsConstructor
public class AiReleaseAuditService {

    private final AiReleaseAuditMapper mapper;

    public void recordModelEnable(GenerationBenchmarkEvidenceRecord evidence,
                                  long operatorUserId,
                                  long modelId) {
        if (evidence == null || evidence.payload() == null || operatorUserId <= 0 || modelId <= 0) {
            throw new IllegalArgumentException("AI release audit identity is incomplete");
        }
        AiReleaseAuditEntity entity = AiReleaseAuditEntity.builder()
                .auditId(UUID.randomUUID().toString())
                .evidenceId(evidence.evidenceId())
                .subjectType(evidence.payload().subjectType().name())
                .subjectKey(evidence.payload().subjectKey())
                .candidateFingerprint(evidence.payload().candidateFingerprint())
                .action("ENABLE")
                .operatorUserId(operatorUserId)
                .releaseReference(Long.toString(modelId))
                .createTime(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        if (mapper.insertAudit(entity) != 1) {
            throw new IllegalStateException("AI release audit could not be persisted");
        }
    }
}
