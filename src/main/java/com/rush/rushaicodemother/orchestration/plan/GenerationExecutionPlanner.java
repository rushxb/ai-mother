package com.rush.rushaicodemother.orchestration.plan;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceSelector;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.context.AiContextPackBudgetProperties;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentSemanticComplexity;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaEnvelope;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaPolicy;
import org.springframework.stereotype.Service;

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

    public GenerationExecutionPlanner(GenerationSlaPolicy generationSlaPolicy,
                                      GenerationPerformanceSelector generationPerformanceSelector,
                                      AiContextPackBudgetProperties contextBudgetProperties) {
        this.generationSlaPolicy = Objects.requireNonNull(generationSlaPolicy, "SLA 策略不能为空");
        this.generationPerformanceSelector = Objects.requireNonNull(
                generationPerformanceSelector, "模型档位选择器不能为空");
        this.contextBudgetProperties = Objects.requireNonNull(
                contextBudgetProperties, "上下文预算配置不能为空");
    }

    /** 根据已完成的意图画像和主路由决策生成不可变执行计划。 */
    public GenerationExecutionPlan plan(GenerationPipelineRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("生成流水线请求不能为空");
        }
        GenerationModeDecision route = Objects.requireNonNull(
                request.modeDecision(), "生成路由决策不能为空");
        IntentProfile intentProfile = request.intentProfile();
        GenerationSlaEnvelope sla = Objects.requireNonNull(
                generationSlaPolicy.resolve(route, request.codeGenType()),
                "SLA 策略返回结果不能为空");
        GenerationPerformanceProfile modelProfile = Objects.requireNonNull(
                generationPerformanceSelector.select(
                        intentProfile.operationType() == IntentOperationType.CREATE,
                        intentProfile.semanticComplexity() != IntentSemanticComplexity.LOW,
                        request.codeGenType()),
                "模型档位选择结果不能为空");

        GenerationExecutionPlan.ContextBudget contextBudget = new GenerationExecutionPlan.ContextBudget(
                contextBudgetProperties.getGenerationMaxTokens(),
                contextBudgetProperties.getRepairMaxTokens(),
                contextBudgetProperties.getMaxSectionTokens(),
                contextBudgetProperties.getMinimumSectionTokens(),
                contextBudgetProperties.getMaxSemanticMemorySections(),
                contextBudgetProperties.getTokenizerModel(),
                contextBudgetProperties.getTokenSafetyMargin()
        );
        boolean readOnly = route.mode() == GenerationMode.READ_ONLY;
        GenerationExecutionPlan.ToolPolicy toolPolicy = new GenerationExecutionPlan.ToolPolicy(
                readOnly ? 0 : modelProfile.maxToolInvocations(),
                sla.toLimits().limit(GenerationBudgetKind.TOOL_WRITE),
                !readOnly,
                !readOnly
        );
        GenerationExecutionPlan.ValidationGraph validationGraph =
                GenerationExecutionPlan.ValidationGraph.forLevel(route.expectedValidationLevel());
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

    /**
     * 按精化后的意图画像重新推导模型档位，其余执行约束保持冻结。
     *
     * <p>提交阶段已经冻结 SLA、验证图与计费口径，任务运行期不允许再改；
     * 但模型档位只影响模型选择与工具调用上限，意图澄清后重算能让"看起来简单、
     * 实际复杂"的请求拿到匹配的模型，而不必重走一遍提交门禁。</p>
     *
     * @param frozenPlan 提交阶段冻结的执行计划
     * @param refinedProfile 精化后的意图画像
     * @param codeGenType 目标代码生成类型
     * @return 仅替换模型档位后的执行计划；输入不完整时返回原计划
     */
    public GenerationExecutionPlan replanModelProfile(GenerationExecutionPlan frozenPlan,
                                                     IntentProfile refinedProfile,
                                                     CodeGenTypeEnum codeGenType) {
        if (frozenPlan == null || refinedProfile == null) {
            return frozenPlan;
        }
        GenerationPerformanceProfile refinedModelProfile = generationPerformanceSelector.select(
                refinedProfile.operationType() == IntentOperationType.CREATE,
                refinedProfile.semanticComplexity() != IntentSemanticComplexity.LOW,
                codeGenType);
        if (refinedModelProfile == null || refinedModelProfile.equals(frozenPlan.modelProfile())) {
            return frozenPlan;
        }
        return frozenPlan.withModelProfile(refinedModelProfile);
    }
}
