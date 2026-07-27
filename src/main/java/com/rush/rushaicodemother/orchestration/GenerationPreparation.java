package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateResult;

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

    public String qualityGateLevel() {
        return qualityGateResult == null ? "unknown" : qualityGateResult.level();
    }

    public boolean requiresBuildValidation() {
        if (targetType == CodeGenTypeEnum.BACKEND_PROJECT) {
            return true;
        }
        if (targetType != CodeGenTypeEnum.VUE_PROJECT && targetType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return false;
        }
        GenerationArtifact generationSpec = artifacts == null ? null : artifacts.get("generation_spec");
        if (generationSpec == null || generationSpec.payload() == null) {
            return true;
        }
        Object requiresBuild = generationSpec.payload().get("requiresBuild");
        return requiresBuild == null || Boolean.TRUE.equals(requiresBuild);
    }

    /**
     * 返回 Planner 基于原始用户需求生成的复杂度结论。
     * 旧检查点或不完整 artifact 不在此处猜测，由调用方选择质量优先的兼容策略。
     */
    public Optional<Boolean> plannedComplexity() {
        GenerationArtifact requirements = artifact("requirements");
        if (requirements == null || requirements.payload() == null) {
            return Optional.empty();
        }
        Object complex = requirements.payload().get("complex");
        return complex instanceof Boolean value ? Optional.of(value) : Optional.empty();
    }

    public GenerationArtifact artifact(String key) {
        return artifacts == null ? null : artifacts.get(key);
    }

    public void putArtifact(GenerationArtifact artifact) {
        if (artifacts == null || artifact == null) {
            return;
        }
        artifacts.put(artifact.key(), artifact);
    }
}
