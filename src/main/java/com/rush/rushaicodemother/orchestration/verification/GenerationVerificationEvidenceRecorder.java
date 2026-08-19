package com.rush.rushaicodemother.orchestration.verification;

import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;

import java.util.Optional;

/** 将已执行通过的验证步骤沉淀为可恢复的结构化制品。 */
public final class GenerationVerificationEvidenceRecorder {

    public static final String ARTIFACT_KEY = GenerationVerificationEvidenceArtifact.KEY;

    /** 仅记录验证器返回的实际观测结果。 */
    public static void recordPassed(GenerationPreparation preparation,
                                    GenerationValidationObservation observation) {
        if (preparation == null || observation == null) {
            throw new IllegalArgumentException("生成准备和验证观测不能为空");
        }
        GenerationVerificationEvidenceArtifact evidence = currentEvidence(preparation)
                .map(current -> current.merge(observation))
                .orElseGet(() -> GenerationVerificationEvidenceArtifact
                        .fromObservation(observation, preparation.targetType()));
        preparation.putArtifact(evidence.toArtifact());
    }

    /** 读取最近一次持久化的实际验证观测；结构不完整时失败关闭。 */
    public static Optional<GenerationValidationObservation> latestObservation(
            GenerationPreparation preparation
    ) {
        if (preparation == null) {
            return Optional.empty();
        }
        return currentEvidence(preparation)
                .map(GenerationVerificationEvidenceArtifact::toObservation);
    }

    private static Optional<GenerationVerificationEvidenceArtifact> currentEvidence(
            GenerationPreparation preparation) {
        GenerationArtifact artifact = preparation.artifact(ARTIFACT_KEY);
        if (artifact == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(GenerationVerificationEvidenceArtifact.fromArtifact(
                    artifact, preparation.targetType()));
        } catch (IllegalArgumentException invalidEvidence) {
            return Optional.empty();
        }
    }
}
