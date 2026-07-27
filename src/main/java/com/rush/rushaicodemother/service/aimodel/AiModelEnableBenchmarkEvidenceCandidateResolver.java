package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidate;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidateIdentity;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidateResolver;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubject;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkPromptFingerprintProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 根据数据库中的停用模型生成与真实启用操作完全一致的候选指纹。 */
@Component
@RequiredArgsConstructor
public class AiModelEnableBenchmarkEvidenceCandidateResolver
        implements GenerationBenchmarkEvidenceCandidateResolver {

    private final AiModelPersistenceService persistenceService;
    private final AiModelConfigurationPolicy configurationPolicy;
    private final AiModelCandidateFingerprintService fingerprintService;
    private final AiModelFleetFingerprintService fleetFingerprintService;
    private final GenerationBenchmarkPromptFingerprintProvider promptFingerprintProvider;

    @Override
    public boolean supports(GenerationBenchmarkEvidenceCandidate candidate) {
        return candidate instanceof GenerationBenchmarkEvidenceCandidate.AiModelEnable;
    }

    @Override
    public GenerationBenchmarkEvidenceCandidateIdentity resolve(
            GenerationBenchmarkEvidenceCandidate candidate) {
        if (!(candidate instanceof GenerationBenchmarkEvidenceCandidate.AiModelEnable modelCandidate)
                || modelCandidate.modelId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "AI 模型候选编号无效");
        }
        AiModelConfiguration configuration = persistenceService.findActiveById(
                modelCandidate.modelId());
        if (configuration == null || configuration.enabled()) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "AI 模型候选必须是当前存在且处于停用状态的模型"
            );
        }
        AiModelConfiguration enableCandidate = configurationPolicy.normalizeAndValidate(
                configuration.toBuilder().isEnabled(1).build());
        String fingerprint = fingerprintService.fingerprint(enableCandidate);
        return new GenerationBenchmarkEvidenceCandidateIdentity(
                GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE,
                Long.toString(modelCandidate.modelId()),
                fingerprint,
                fleetFingerprintService.fingerprintWithEnabledCandidate(enableCandidate),
                promptFingerprintProvider.currentDurableFingerprint()
        );
    }
}
