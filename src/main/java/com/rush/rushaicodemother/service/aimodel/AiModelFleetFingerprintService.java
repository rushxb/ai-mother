package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkModelFingerprintProvider;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.ReleaseCandidateFingerprint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Stream;

/** 对当前已启用模型池生成稳定且不可逆的 Benchmark 身份。 */
@Service
@RequiredArgsConstructor
public class AiModelFleetFingerprintService implements GenerationBenchmarkModelFingerprintProvider {

    private final AiModelEnabledConfigurationSource configurationSource;
    private final AiModelPersistenceService persistenceService;
    private final AiModelCandidateFingerprintService candidateFingerprintService;
    private final AiModelRuntimeProperties runtimeProperties;

    @Override
    public String currentFingerprint() {
        return fingerprint(configurationSource.findEnabled(null).stream());
    }

    public String currentPersistentFingerprint() {
        return fingerprint(persistenceService.findEnabled(null).stream());
    }

    public String fingerprintWithEnabledCandidate(AiModelConfiguration candidate) {
        if (candidate == null || !candidate.enabled()) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "Benchmark 模型池候选必须是已规范化的启用配置"
            );
        }
        return fingerprint(Stream.concat(
                persistenceService.findEnabled(null).stream(),
                Stream.of(candidate)
        ));
    }

    private String fingerprint(Stream<AiModelConfiguration> configurations) {
        List<String> modelFingerprints = configurations
                .map(candidateFingerprintService::fingerprint)
                .sorted()
                .toList();
        if (modelFingerprints.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "没有可用于 Benchmark 来源校验的已启用 AI 模型"
            );
        }
        StringBuilder canonical = new StringBuilder("generation-benchmark-model-fleet-v2|");
        modelFingerprints.forEach(fingerprint ->
                ReleaseCandidateFingerprint.appendField(canonical, fingerprint));
        ReleaseCandidateFingerprint.appendField(
                canonical, String.valueOf(runtimeProperties.getFailoverMaxCandidates()));
        ReleaseCandidateFingerprint.appendField(
                canonical, String.valueOf(runtimeProperties.isFirstTokenHedgeEnabled()));
        if (runtimeProperties.isFirstTokenHedgeEnabled()) {
            ReleaseCandidateFingerprint.appendField(
                    canonical, String.valueOf(runtimeProperties.getFirstTokenHedgeDelay()));
            ReleaseCandidateFingerprint.appendField(
                    canonical,
                    String.valueOf(runtimeProperties.isFirstTokenHedgeRequireDistinctProvider()));
        }
        return ReleaseCandidateFingerprint.sha256(canonical.toString());
    }
}
