package com.rush.rushaicodemother.orchestration.verification;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 将已执行通过的验证步骤沉淀为可恢复的结构化制品。 */
public final class GenerationVerificationEvidenceRecorder {

    public static final String ARTIFACT_KEY = "verification_evidence";

    /** 仅记录验证器返回的实际观测结果。 */
    public static void recordPassed(GenerationPreparation preparation,
                                    GenerationValidationObservation observation) {
        if (preparation == null || observation == null) {
            throw new IllegalArgumentException("生成准备和验证观测不能为空");
        }
        LinkedHashSet<String> passedSteps = new LinkedHashSet<>(existingPassedSteps(preparation));
        observation.passedSteps().stream()
                .sorted()
                .map(Enum::name)
                .forEach(passedSteps::add);
        preparation.putArtifact(GenerationArtifact.of(
                ARTIFACT_KEY,
                "Verification",
                "工程验证证据",
                Map.of(
                        "status", "passed",
                        "source", observation.source(),
                        "targetType", observation.targetType().getValue(),
                        "passedSteps", List.copyOf(passedSteps),
                        "details", observation.details()
                )
        ));
    }

    /** 读取最近一次持久化的实际验证观测；结构不完整时失败关闭。 */
    public static Optional<GenerationValidationObservation> latestObservation(
            GenerationPreparation preparation
    ) {
        if (preparation == null) {
            return Optional.empty();
        }
        GenerationArtifact artifact = preparation.artifact(ARTIFACT_KEY);
        if (artifact == null || artifact.payload() == null
                || !"passed".equals(artifact.payload().get("status"))) {
            return Optional.empty();
        }
        Object rawTargetType = artifact.payload().get("targetType");
        Object rawSource = artifact.payload().get("source");
        Object rawSteps = artifact.payload().get("passedSteps");
        CodeGenTypeEnum targetType = rawTargetType instanceof String value
                ? CodeGenTypeEnum.getEnumByValue(value)
                : null;
        if (targetType == null || !(rawSource instanceof String source)
                || !(rawSteps instanceof List<?> steps)) {
            return Optional.empty();
        }
        EnumSet<GenerationExecutionPlan.ValidationStep> passedSteps =
                EnumSet.noneOf(GenerationExecutionPlan.ValidationStep.class);
        try {
            for (Object step : steps) {
                if (!(step instanceof String stepName)) {
                    return Optional.empty();
                }
                passedSteps.add(GenerationExecutionPlan.ValidationStep.valueOf(stepName));
            }
        } catch (IllegalArgumentException invalidStep) {
            return Optional.empty();
        }
        if (passedSteps.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> details = stringKeyedMap(artifact.payload().get("details"));
        return Optional.of(GenerationValidationObservation.passed(
                targetType, source, passedSteps, details));
    }

    private static Map<String, Object> stringKeyedMap(Object rawDetails) {
        if (!(rawDetails instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> {
            if (key instanceof String text && value != null) {
                details.put(text, value);
            }
        });
        return details;
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
