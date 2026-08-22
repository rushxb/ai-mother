package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationSpecificationArtifact;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationNodeReplayPolicy;
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
        super("buildfix", "BuildFix", "buildfix", List.of("review"),
                GenerationNodeReplayPolicy.REPLAY_SAFE);
    }

    /**
 * 执行构建{@code Fix}智能体节点处理流程。
 *
 * @param context 执行上下文
 * @return 构建{@code Fix}智能体节点
 */
    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        GenerationSpecificationArtifact specification = context
                .getArtifact(GenerationSpecificationArtifact.KEY)
                .map(GenerationSpecificationArtifact::fromArtifact)
                .orElseThrow(() -> new IllegalArgumentException("缺少生成规范制品"));
        boolean requiresBuild = specification.requiresBuild();
        boolean patchFirst = specification.patchFirst();
        String validationMode = specification.validationMode();
        String generationMode = specification.generationMode();
        ChangePlan changePlan = context.getArtifact(ChangePlan.KEY)
                .map(ChangePlan::fromArtifact)
                .orElse(null);
        String rollbackStrategy = changePlan == null
                ? (requiresBuild ? "rollback_to_last_stable_snapshot_or_manual_retry" : "manual_retry_without_snapshot")
                : changePlan.rollbackStrategy();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requiresBuild", requiresBuild);
        payload.put("repairRounds", requiresBuild ? 1 : 0);
        payload.put("rollbackOnFailure", requiresBuild || (changePlan != null && changePlan.requiresSnapshotRollback()));
        payload.put("recoverable", true);
        payload.put("patchFirst", patchFirst);
        payload.put("validationMode", validationMode);
        payload.put("generationMode", generationMode);
        payload.put("enabled", requiresBuild);
        payload.put("rollbackStrategy", rollbackStrategy);
        payload.put("impactedModules", changePlan == null ? List.of() : changePlan.impactedModules());
        payload.put("fileChangeCount", changePlan == null ? 0 : changePlan.fileChangeCount());
        GenerationArtifact artifact = GenerationArtifact.of("buildfix_plan", "BuildFix", "构建修复策略", payload);
        return AgentNodeResult.of(
                requiresBuild ? "已配置构建校验、自动修复和失败回退策略" : "当前模式无需 BuildFix，仅保留失败回退策略",
                List.of(artifact),
                payload
        );
    }
}
