package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.ContextSummaryArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationSpecificationArtifact;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationNodeReplayPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 仅准备模板、项目上下文和运行时必需生成规范的无规划基线。 */
public class NoPlanningAgentNode extends BaseGenerationAgentNode {

    private final TemplateAgentNode templateNode;
    private final ContextAgentNode contextNode;

    public NoPlanningAgentNode(TemplateAgentNode templateNode, ContextAgentNode contextNode) {
        super("no_plan", "NoPlan", "planning", List.of(),
                GenerationNodeReplayPolicy.REQUIRES_START_CHECKPOINT);
        this.templateNode = templateNode;
        this.contextNode = contextNode;
    }

    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        CodeGenTypeEnum routedType = context.getRequest().routingFunction() == null
                ? context.getRequest().currentType()
                : context.getRequest().routingFunction().apply(context.getRequest().userMessage());
        context.setTargetType(CodeGenTypeEnum.max(context.getRequest().currentType(), routedType));
        context.setUpgradeRequired(context.getRequest().currentType().canUpgradeTo(context.getTargetType()));

        List<GenerationArtifact> artifacts = new ArrayList<>();
        append(templateNode.execute(context), context, artifacts);
        append(contextNode.execute(context), context, artifacts);

        boolean patchFirst = context.getRequest().hasGeneratedCode();
        String validationMode = context.isHeavyPath() ? "build_validation" : "review_only";
        List<String> selectedFiles = context.getArtifact(ContextSummaryArtifact.KEY)
                .map(ContextSummaryArtifact::fromArtifact)
                .map(ContextSummaryArtifact::selectedFiles)
                .orElseThrow(() -> new IllegalStateException("缺少项目上下文制品，无法生成无规划变更边界"));
        ChangePlan changePlan = new ChangePlan(
                "v1",
                patchFirst ? "targeted_update" : "project_bootstrap",
                List.of(),
                selectedFiles,
                List.of(),
                List.of(),
                validationMode,
                context.isHeavyPath()
                        ? "rollback_to_last_stable_snapshot_or_manual_retry"
                        : "manual_retry_without_snapshot"
        );
        Map<String, Object> specificationDetails = new LinkedHashMap<>();
        specificationDetails.put("modulePlan", List.of());
        specificationDetails.put("parallelModuleCount", 0);
        specificationDetails.put("executionMode", "unplanned_generation");
        specificationDetails.put("changePlan", changePlan.toPayload());
        GenerationArtifact changePlanArtifact = changePlan.toArtifact("NoPlan", "最小变更边界");
        GenerationArtifact generationSpec = GenerationSpecificationArtifact.execution(
                context.getRequest().userMessage(),
                patchFirst,
                context.isHeavyPath(),
                specificationDetails
        ).toArtifact("NoPlan", "无规划生成规范");
        artifacts.add(changePlanArtifact);
        artifacts.add(generationSpec);
        return AgentNodeResult.of(
                "已准备无规划生成基线",
                artifacts,
                Map.of("planningVariant", "NO_PLAN")
        );
    }

    private void append(AgentNodeResult result,
                        GenerationAgentContext context,
                        List<GenerationArtifact> artifacts) {
        context.putArtifacts(result.artifacts());
        artifacts.addAll(result.artifacts());
    }

}
