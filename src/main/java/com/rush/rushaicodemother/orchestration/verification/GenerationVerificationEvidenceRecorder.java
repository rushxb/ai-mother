package com.rush.rushaicodemother.orchestration.verification;

import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;

import java.util.Optional;

/** 将已执行通过的验证步骤沉淀为可恢复的结构化制品。 */
public final class GenerationVerificationEvidenceRecorder {

    public static final String ARTIFACT_KEY = GenerationVerificationEvidenceArtifact.KEY;

    /** 仅记录验证器返回的实际观测结果。 */
    public static void recordPassed(GenerationPreparation preparation,
                                    GenerationSession session,
                                    GenerationValidationObservation observation) {
        if (preparation == null || session == null || observation == null) {
            throw new IllegalArgumentException("生成准备、会话和验证观测不能为空");
        }
        GenerationVerificationEvidenceArtifact.VerificationSubject subject =
                GenerationVerificationEvidenceArtifact.currentSubject(preparation, session);
        GenerationVerificationEvidenceArtifact evidence = currentEvidence(preparation, subject)
                .map(current -> current.merge(observation))
                .orElseGet(() -> GenerationVerificationEvidenceArtifact
                        .fromObservation(observation, subject));
        preparation.putArtifact(evidence.toArtifact());
    }

    /** 读取最近一次记录的实际验证观测；结构不完整或执行主体变化时失败关闭。 */
    public static Optional<GenerationValidationObservation> latestObservation(
            GenerationPreparation preparation,
            GenerationSession session
    ) {
        if (preparation == null) {
            return Optional.empty();
        }
        GenerationArtifact artifact = preparation.artifact(ARTIFACT_KEY);
        if (artifact == null) {
            return Optional.empty();
        }
        try {
            GenerationVerificationEvidenceArtifact.VerificationSubject subject =
                    GenerationVerificationEvidenceArtifact.currentSubject(preparation, session);
            return currentEvidence(preparation, subject)
                    .map(GenerationVerificationEvidenceArtifact::toObservation);
        } catch (IllegalArgumentException invalidSubject) {
            return Optional.empty();
        }
    }

    private static Optional<GenerationVerificationEvidenceArtifact> currentEvidence(
            GenerationPreparation preparation,
            GenerationVerificationEvidenceArtifact.VerificationSubject subject) {
        GenerationArtifact artifact = preparation.artifact(ARTIFACT_KEY);
        if (artifact == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(GenerationVerificationEvidenceArtifact.fromArtifact(
                    artifact, subject));
        } catch (IllegalArgumentException invalidEvidence) {
            return Optional.empty();
        }
    }
}
