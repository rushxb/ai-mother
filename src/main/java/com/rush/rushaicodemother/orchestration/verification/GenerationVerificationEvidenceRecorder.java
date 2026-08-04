package com.rush.rushaicodemother.orchestration.verification;

import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 将已执行通过的验证步骤沉淀为可恢复的结构化制品。 */
public final class GenerationVerificationEvidenceRecorder {

    public static final String ARTIFACT_KEY = "verification_evidence";

    public static void recordPassed(GenerationPreparation preparation,
                                    GenerationVerificationPolicy verificationPolicy,
                                    String source) {
        if (preparation == null || verificationPolicy == null) {
            throw new IllegalArgumentException("生成准备和验证策略不能为空");
        }
        LinkedHashSet<String> passedSteps = new LinkedHashSet<>(existingPassedSteps(preparation));
        passedSteps.add(GenerationExecutionPlan.ValidationStep.FAST_CHECK.name());
        if (verificationPolicy.requiresBuild()) {
            passedSteps.add(GenerationExecutionPlan.ValidationStep.BUILD.name());
        }
        if (verificationPolicy.requiresExpertCheck()) {
            passedSteps.add(GenerationExecutionPlan.ValidationStep.EXPERT_CHECK.name());
        }
        preparation.putArtifact(GenerationArtifact.of(
                ARTIFACT_KEY,
                "Verification",
                "工程验证证据",
                Map.of(
                        "status", "passed",
                        "source", source == null || source.isBlank() ? "generation_validation" : source.trim(),
                        "passedSteps", List.copyOf(passedSteps)
                )
        ));
    }

    private static List<String> existingPassedSteps(GenerationPreparation preparation) {
        GenerationArtifact artifact = preparation.artifact(ARTIFACT_KEY);
        if (artifact == null || artifact.payload() == null) {
            return List.of();
        }
        Object rawSteps = artifact.payload().get("passedSteps");
        if (!(rawSteps instanceof List<?> steps)) {
            return List.of();
        }
        return steps.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
