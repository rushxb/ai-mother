package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationRequirementsArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationSpecificationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateResult;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record GenerationPreparation(CodeGenTypeEnum originalType,
                                    CodeGenTypeEnum targetType,
                                    boolean upgradeRequired,
                                    String generatingStage,
                                    String enhancedMessage,
                                    List<GenerationStreamEvent> events,
                                    Map<String, GenerationArtifact> artifacts,
                                    QualityGateResult qualityGateResult,
                                    Map<String, Long> timings,
                                    String taskId) {

    private static final String VALIDATION_POLICY_ARTIFACT = "validation_policy";
    private static final String REQUIRES_BUILD_FIELD = "requiresBuild";

    public String qualityGateLevel() {
        return qualityGateResult == null ? "unknown" : qualityGateResult.level();
    }

    /**
     * 返回当前生成准备是否必须执行构建校验。
     *
     * <p>Vue 与全栈工程只有在生成规范显式给出布尔值 {@code false} 时才允许跳过。
     * 旧检查点缺失、载荷损坏或字段类型错误时采用 fail-closed 语义，避免静默降低质量门禁。</p>
     *
     * @return 必须执行构建校验时返回 {@code true}
     */
    public boolean requiresBuildValidation() {
        if (targetType == CodeGenTypeEnum.BACKEND_PROJECT) {
            return true;
        }
        if (targetType != CodeGenTypeEnum.VUE_PROJECT && targetType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return false;
        }
        GenerationArtifact validationPolicy = artifact(VALIDATION_POLICY_ARTIFACT);
        if (validationPolicy != null
                && validationPolicy.payload() != null
                && Boolean.TRUE.equals(validationPolicy.payload().get(REQUIRES_BUILD_FIELD))) {
            return true;
        }
        return GenerationSpecificationArtifact.requiresBuildOrDefault(
                artifact(GenerationSpecificationArtifact.KEY),
                true
        );
    }

    /** 将路由层声明的最低校验级别固化到可恢复的生成准备中。 */
    public GenerationPreparation enforceValidationFloor(ExpectedValidationLevel validationLevel) {
        if (validationLevel == null
                || validationLevel == ExpectedValidationLevel.FAST
                || (targetType != CodeGenTypeEnum.VUE_PROJECT
                && targetType != CodeGenTypeEnum.BACKEND_PROJECT
                && targetType != CodeGenTypeEnum.FULL_STACK_PROJECT)) {
            return this;
        }
        Map<String, GenerationArtifact> updatedArtifacts = new LinkedHashMap<>(
                artifacts == null ? Map.of() : artifacts);
        updatedArtifacts.put(VALIDATION_POLICY_ARTIFACT, GenerationArtifact.of(
                VALIDATION_POLICY_ARTIFACT,
                "ExecutionPolicy",
                "最低校验策略",
                Map.of(
                        "minimumLevel", validationLevel.name(),
                        "requiresBuild", true,
                        "source", "route_decision"
                )
        ));
        return new GenerationPreparation(
                originalType,
                targetType,
                upgradeRequired,
                generatingStage,
                enhancedMessage,
                events,
                updatedArtifacts,
                qualityGateResult,
                timings,
                taskId
        );
    }

    /**
     * 返回 Planner 基于原始用户需求生成的复杂度结论。
     * 旧检查点或不完整 artifact 不在此处猜测，由调用方选择质量优先的兼容策略。
     */
    public Optional<Boolean> plannedComplexity() {
        GenerationArtifact requirements = artifact(GenerationRequirementsArtifact.KEY);
        if (requirements == null) {
            return Optional.empty();
        }
        return GenerationRequirementsArtifact
                .fromArtifact(requirements, targetType)
                .plannedComplexity();
    }

    public GenerationArtifact artifact(String key) {
        return artifacts == null ? null : artifacts.get(key);
    }

    /**
 * 处理{@code put}制品。
 *
 * @param artifact 制品
 */
    public void putArtifact(GenerationArtifact artifact) {
        if (artifacts == null || artifact == null) {
            return;
        }
        artifacts.put(artifact.key(), artifact);
    }
}
