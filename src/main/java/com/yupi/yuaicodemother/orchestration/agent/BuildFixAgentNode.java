package com.yupi.yuaicodemother.orchestration.agent;

import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.dag.AgentNodeResult;
import com.yupi.yuaicodemother.orchestration.dag.GenerationAgentContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BuildFix：定义后置构建修复策略。
 */
@Component
public class BuildFixAgentNode extends BaseGenerationAgentNode {

    public BuildFixAgentNode() {
        super("buildfix", "BuildFix", "buildfix", List.of("review"));
    }

    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        boolean requiresBuild = Boolean.TRUE.equals(context.getArtifactValue("generation_spec", "requiresBuild"));
        boolean patchFirst = Boolean.TRUE.equals(context.getArtifactValue("generation_spec", "patchFirst"));
        String validationMode = stringArtifactValue(context, "generation_spec", "validationMode",
                requiresBuild ? "build_validation" : "review_only");
        String generationMode = stringArtifactValue(context, "generation_spec", "generationMode",
                patchFirst ? "patch_first_update" : "full_generation");
        String rollbackStrategy = stringArtifactValue(context, "change_plan", "rollbackStrategy",
                requiresBuild ? "rollback_to_last_stable_snapshot_or_manual_retry" : "manual_retry_without_snapshot");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requiresBuild", requiresBuild);
        payload.put("repairRounds", requiresBuild ? 1 : 0);
        payload.put("rollbackOnFailure", true);
        payload.put("recoverable", true);
        payload.put("patchFirst", patchFirst);
        payload.put("validationMode", validationMode);
        payload.put("generationMode", generationMode);
        payload.put("enabled", requiresBuild);
        payload.put("rollbackStrategy", rollbackStrategy);
        payload.put("impactedModules", context.getArtifactValue("change_plan", "impactedModules"));
        GenerationArtifact artifact = GenerationArtifact.of("buildfix_plan", "BuildFix", "构建修复策略", payload);
        return AgentNodeResult.of(
                requiresBuild ? "已配置构建校验、自动修复和失败回退策略" : "当前模式无需 BuildFix，仅保留失败回退策略",
                List.of(artifact),
                payload
        );
    }

    private String stringArtifactValue(GenerationAgentContext context,
                                       String artifactKey,
                                       String payloadKey,
                                       String defaultValue) {
        Object value = context.getArtifactValue(artifactKey, payloadKey);
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return String.valueOf(value);
    }
}
