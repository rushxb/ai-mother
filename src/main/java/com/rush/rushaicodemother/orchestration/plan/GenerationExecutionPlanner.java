package com.rush.rushaicodemother.orchestration.plan;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceSelector;
import com.rush.rushaicodemother.orchestration.context.AiContextPackBudgetProperties;
import com.rush.rushaicodemother.orchestration.decision.GenerationMutability;
import com.rush.rushaicodemother.orchestration.decision.GenerationPreflightUsage;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentSemanticComplexity;
import com.rush.rushaicodemother.orchestration.governance.app.AppGenerationControlPolicy;
import com.rush.rushaicodemother.orchestration.governance.app.AppGenerationControlReader;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaEnvelope;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaPolicy;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;

/**
 * 在任务进入运行时前一次性解析所有执行约束。
 *
 * <p>该服务只读取结构化意图、路由决策和配置快照，不持有或输出业务标识。</p>
 */
@Service
public class GenerationExecutionPlanner {

    private final GenerationSlaPolicy generationSlaPolicy;
    private final GenerationPerformanceSelector generationPerformanceSelector;
    private final AiContextPackBudgetProperties contextBudgetProperties;
    private final AppGenerationControlReader appControlReader;

    @Autowired
    public GenerationExecutionPlanner(GenerationSlaPolicy generationSlaPolicy,
                                      GenerationPerformanceSelector generationPerformanceSelector,
                                      AiContextPackBudgetProperties contextBudgetProperties,
                                      AppGenerationControlReader appControlReader) {
        this.generationSlaPolicy = Objects.requireNonNull(generationSlaPolicy, "SLA 策略不能为空");
        this.generationPerformanceSelector = Objects.requireNonNull(
                generationPerformanceSelector, "模型档位选择器不能为空");
        this.contextBudgetProperties = Objects.requireNonNull(
                contextBudgetProperties, "上下文预算配置不能为空");
        this.appControlReader = Objects.requireNonNull(
                appControlReader, "应用生成控制读取器不能为空");
    }

    /** 兼容不携带应用身份的规划单元测试。 */
    public GenerationExecutionPlanner(GenerationSlaPolicy generationSlaPolicy,
                                      GenerationPerformanceSelector generationPerformanceSelector,
                                      AiContextPackBudgetProperties contextBudgetProperties) {
        this(generationSlaPolicy, generationPerformanceSelector, contextBudgetProperties,
                AppGenerationControlReader.defaultsOnly());
    }

    /** 根据已完成的意图画像和主路由决策生成不可变执行计划。 */
    public GenerationExecutionPlan plan(GenerationPipelineRequest request) {
        return plan(request, GenerationPreflightUsage.none());
    }

    /** 根据最终场景决策生成计划，并将提交前模型消耗纳入总任务预算。 */
    public GenerationExecutionPlan plan(GenerationPipelineRequest request,
                                        GenerationPreflightUsage preflightUsage) {
        if (request == null) {
            throw new IllegalArgumentException("生成流水线请求不能为空");
        }
        GenerationPreflightUsage effectiveUsage = preflightUsage == null
                ? GenerationPreflightUsage.none()
                : preflightUsage;
        GenerationScenarioDecision scenarioDecision = Objects.requireNonNull(
                request.scenarioDecision(), "场景决策不能为空");
        GenerationModeDecision route = scenarioDecision.routeDecision();
        IntentProfile intentProfile = scenarioDecision.intentProfile();
        GenerationSlaEnvelope routeSla = Objects.requireNonNull(
                generationSlaPolicy.resolve(route, request.codeGenType()),
                "SLA 策略返回结果不能为空");
        GenerationSlaEnvelope sla = effectiveUsage.includeIn(routeSla);
        GenerationPerformanceProfile modelProfile = Objects.requireNonNull(
                generationPerformanceSelector.select(
                        intentProfile.operationType() == IntentOperationType.CREATE,
                        intentProfile.semanticComplexity() != IntentSemanticComplexity.LOW,
                        request.codeGenType()),
                "模型档位选择结果不能为空");
        modelProfile = applyApplicationModelPolicy(request, modelProfile);

        GenerationExecutionPlan.ContextBudget contextBudget = new GenerationExecutionPlan.ContextBudget(
                contextBudgetProperties.getGenerationMaxTokens(),
                contextBudgetProperties.getRepairMaxTokens(),
                contextBudgetProperties.getMaxSectionTokens(),
                contextBudgetProperties.getMinimumSectionTokens(),
                contextBudgetProperties.getMaxSemanticMemorySections(),
                contextBudgetProperties.getTokenizerModel(),
                contextBudgetProperties.getTokenSafetyMargin()
        );
        boolean readOnly = scenarioDecision.mutability() == GenerationMutability.READ_ONLY;
        GenerationExecutionPlan.ToolPolicy toolPolicy = new GenerationExecutionPlan.ToolPolicy(
                readOnly ? 0 : modelProfile.maxToolInvocations(),
                sla.toLimits().limit(GenerationBudgetKind.TOOL_WRITE),
                scenarioDecision.toolPermissionProfile().writeFenceRequired(),
                scenarioDecision.toolPermissionProfile().destructiveApprovalRequired()
        );
        GenerationExecutionPlan.ValidationGraph validationGraph =
                GenerationExecutionPlan.ValidationGraph.forLevel(scenarioDecision.validationFloor());
        GenerationExecutionPlan.RepairBudget repairBudget = new GenerationExecutionPlan.RepairBudget(
                sla.toLimits().limit(GenerationBudgetKind.REPAIR_ROUND),
                !readOnly
        );

        return new GenerationExecutionPlan(
                route,
                modelProfile,
                contextBudget,
                toolPolicy,
                validationGraph,
                repairBudget,
                new GenerationExecutionPlan.CommitPolicy(!readOnly, !readOnly),
                new GenerationExecutionPlan.PreviewPolicy(
                        sla.firstPreviewTimeout(), sla.firstPreviewCompletionReserve()),
                sla
        );
    }

    private GenerationPerformanceProfile applyApplicationModelPolicy(
            GenerationPipelineRequest request,
            GenerationPerformanceProfile selectedProfile) {
        Long appId = request.taskRequest() == null || request.taskRequest().app() == null
                ? null
                : request.taskRequest().app().getId();
        if (appId == null || appId <= 0) {
            return selectedProfile;
        }
        AppGenerationControlPolicy policy = appControlReader.get(appId);
        if (policy.emergencyStopped()) {
            throw new GenerationExecutionPolicyException("应用已紧急停止生成");
        }
        return policy.constrainModelProfile(selectedProfile);
    }

}
