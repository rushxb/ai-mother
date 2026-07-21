package com.rush.rushaicodemother.infrastructure.persistence.benchmark;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.mapper.GenerationBenchmarkEvidenceMapper;
import com.rush.rushaicodemother.model.entity.GenerationBenchmarkEvidenceEntity;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidencePayload;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceRecord;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceRepository;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubject;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Insert-only MyBatis adapter for immutable release evidence. */
@Repository
@RequiredArgsConstructor
public class MyBatisGenerationBenchmarkEvidenceRepository
        implements GenerationBenchmarkEvidenceRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final GenerationBenchmarkEvidenceMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public void insert(GenerationBenchmarkEvidenceRecord evidence) {
        if (evidence == null || evidence.payload() == null) {
            throw new IllegalArgumentException("benchmark evidence cannot be null");
        }
        GenerationBenchmarkEvidencePayload payload = evidence.payload();
        GenerationBenchmarkEvidenceEntity entity = GenerationBenchmarkEvidenceEntity.builder()
                .evidenceId(evidence.evidenceId())
                .subjectType(payload.subjectType().name())
                .subjectKey(payload.subjectKey())
                .candidateFingerprint(payload.candidateFingerprint())
                .datasetFingerprint(payload.datasetFingerprint())
                .graderFingerprint(payload.graderFingerprint())
                .runtimeConfigFingerprint(payload.runtimeConfigFingerprint())
                .gitCommit(payload.gitCommit())
                .modelFingerprint(payload.modelFingerprint())
                .promptBundleFingerprint(payload.promptBundleFingerprint())
                .reportSha256(payload.reportSha256())
                .reportJson(evidence.reportJson())
                .passed(evidence.passed() ? 1 : 0)
                .violationsJson(writeViolations(evidence.violations()))
                .signature(evidence.signature())
                .evaluatedAt(toLocal(payload.evaluatedAt()))
                .expiresAt(toLocal(payload.expiresAt()))
                .createTime(toLocal(evidence.createdAt()))
                .build();
        try {
            if (mapper.insertEvidence(entity) != 1) {
                throw new IllegalStateException("benchmark evidence could not be inserted");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("benchmark evidence id already exists", duplicate);
        }
    }

    @Override
    public Optional<GenerationBenchmarkEvidenceRecord> findByEvidenceId(String evidenceId) {
        if (evidenceId == null || evidenceId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectByEvidenceId(evidenceId.trim())).map(this::toRecord);
    }

    private GenerationBenchmarkEvidenceRecord toRecord(GenerationBenchmarkEvidenceEntity entity) {
        if (entity.getEvidenceId() == null || entity.getSubjectType() == null
                || entity.getEvaluatedAt() == null || entity.getExpiresAt() == null
                || entity.getCreateTime() == null || entity.getPassed() == null) {
            throw new IllegalStateException("benchmark evidence row is incomplete");
        }
        GenerationBenchmarkEvidenceSubject subject;
        try {
            subject = GenerationBenchmarkEvidenceSubject.valueOf(entity.getSubjectType());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("benchmark evidence subject is invalid", invalid);
        }
        GenerationBenchmarkEvidencePayload payload = new GenerationBenchmarkEvidencePayload(
                subject,
                entity.getSubjectKey(),
                entity.getCandidateFingerprint(),
                entity.getDatasetFingerprint(),
                entity.getGraderFingerprint(),
                entity.getRuntimeConfigFingerprint(),
                entity.getGitCommit(),
                entity.getModelFingerprint(),
                entity.getPromptBundleFingerprint(),
                entity.getReportSha256(),
                toInstant(entity.getEvaluatedAt()),
                toInstant(entity.getExpiresAt())
        );
        return new GenerationBenchmarkEvidenceRecord(
                entity.getEvidenceId(),
                payload,
                entity.getReportJson(),
                Integer.valueOf(1).equals(entity.getPassed()),
                readViolations(entity.getViolationsJson()),
                entity.getSignature(),
                toInstant(entity.getCreateTime())
        );
    }

    private String writeViolations(List<String> violations) {
        try {
            return objectMapper.writeValueAsString(violations == null ? List.of() : violations);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("benchmark evidence violations could not be serialized", failure);
        }
    }

    private List<String> readViolations(String value) {
        try {
            return value == null || value.isBlank()
                    ? List.of()
                    : List.copyOf(objectMapper.readValue(value, STRING_LIST));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("benchmark evidence violations are corrupted", failure);
        }
    }

    private LocalDateTime toLocal(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private Instant toInstant(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }
}
