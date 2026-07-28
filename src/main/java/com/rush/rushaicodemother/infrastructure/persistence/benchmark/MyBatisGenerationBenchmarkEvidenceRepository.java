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

/** 不可变发布证据的只增 MyBatis 持久化适配器。 */
@Repository
@RequiredArgsConstructor
public class MyBatisGenerationBenchmarkEvidenceRepository
        implements GenerationBenchmarkEvidenceRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final GenerationBenchmarkEvidenceMapper mapper;
    private final ObjectMapper objectMapper;

    /**
 * 处理{@code insert}。
 *
 * @param evidence 证据
 */
    @Override
    public void insert(GenerationBenchmarkEvidenceRecord evidence) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (evidence == null || evidence.payload() == null) {
            throw new IllegalArgumentException("Benchmark 证据不能为空");
        }
        GenerationBenchmarkEvidencePayload payload = evidence.payload();
        GenerationBenchmarkEvidenceEntity entity = GenerationBenchmarkEvidenceEntity.builder()
                .evidenceId(evidence.evidenceId())
                .subjectType(payload.subjectType().name())
                .subjectKey(payload.subjectKey())
                .candidateFingerprint(payload.candidateFingerprint())
                .signatureVersion(payload.signatureVersion())
                .candidatePhysicalRequestCount(payload.candidatePhysicalRequestCount())
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
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            if (mapper.insertEvidence(entity) != 1) {
                throw new IllegalStateException("Benchmark 证据无法入库");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Benchmark 证据标识已存在", duplicate);
        }
    }

    /**
 * 查找匹配的按证据编号。
 *
 * @param evidenceId 证据编号
 * @return 可选的按证据编号；不存在时返回空值
 */
    @Override
    public Optional<GenerationBenchmarkEvidenceRecord> findByEvidenceId(String evidenceId) {
        if (evidenceId == null || evidenceId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectByEvidenceId(evidenceId.trim())).map(this::toRecord);
    }

    /** 将当前对象转换为记录。 */
    private GenerationBenchmarkEvidenceRecord toRecord(GenerationBenchmarkEvidenceEntity entity) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (entity.getEvidenceId() == null || entity.getSubjectType() == null
                || entity.getSignatureVersion() == null
                || entity.getCandidatePhysicalRequestCount() == null
                || entity.getEvaluatedAt() == null || entity.getExpiresAt() == null
                || entity.getCreateTime() == null || entity.getPassed() == null) {
            throw new IllegalStateException("Benchmark 证据数据行不完整");
        }
        GenerationBenchmarkEvidenceSubject subject;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            subject = GenerationBenchmarkEvidenceSubject.valueOf(entity.getSubjectType());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Benchmark 证据候选类型无效", invalid);
        }
        GenerationBenchmarkEvidencePayload payload = new GenerationBenchmarkEvidencePayload(
                entity.getSignatureVersion(),
                subject,
                entity.getSubjectKey(),
                entity.getCandidateFingerprint(),
                entity.getCandidatePhysicalRequestCount(),
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

    /** 写入{@code Violations}。 */
    private String writeViolations(List<String> violations) {
        try {
            return objectMapper.writeValueAsString(violations == null ? List.of() : violations);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Benchmark 证据违规项无法序列化", failure);
        }
    }

    /** 读取{@code Violations}。 */
    private List<String> readViolations(String value) {
        try {
            return value == null || value.isBlank()
                    ? List.of()
                    : List.copyOf(objectMapper.readValue(value, STRING_LIST));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Benchmark 证据违规项数据已损坏", failure);
        }
    }

    private LocalDateTime toLocal(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private Instant toInstant(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }
}
