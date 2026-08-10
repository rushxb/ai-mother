package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

/** 生成流水线请求参数。 */
public record GenerationPipelineRequest(
        GenerationTaskRequest taskRequest,
        CodeGenTypeEnum codeGenType,
        GenerationWorkspace workspace,
        IntentProfile intentProfile,
        GenerationModeDecision modeDecision,
        GenerationExecutionPlan executionPlan,
        GenerationTaskExecution execution
) {

    public GenerationPipelineRequest {
        intentProfile = intentProfile == null ? IntentProfile.unknown() : intentProfile;
        if (executionPlan != null && !executionPlan.route().equals(modeDecision)) {
            throw new IllegalArgumentException("执行计划路由必须与流水线路由决策一致");
        }
    }

    /** 兼容尚未携带执行计划的调用方。 */
    public GenerationPipelineRequest(GenerationTaskRequest taskRequest,
                                     CodeGenTypeEnum codeGenType,
                                     GenerationWorkspace workspace,
                                     IntentProfile intentProfile,
                                     GenerationModeDecision modeDecision,
                                     GenerationTaskExecution execution) {
        this(taskRequest, codeGenType, workspace, intentProfile, modeDecision, null, execution);
    }

    public GenerationPipelineRequest(GenerationTaskRequest taskRequest,
                                     CodeGenTypeEnum codeGenType,
                                     GenerationWorkspace workspace,
                                     GenerationModeDecision modeDecision) {
        this(taskRequest, codeGenType, workspace, IntentProfile.unknown(), modeDecision, null, null);
    }

    public GenerationPipelineRequest(GenerationTaskRequest taskRequest,
                                     CodeGenTypeEnum codeGenType,
                                     GenerationWorkspace workspace,
                                     IntentProfile intentProfile,
                                     GenerationModeDecision modeDecision) {
        this(taskRequest, codeGenType, workspace, intentProfile, modeDecision, null, null);
    }

    public GenerationPipelineRequest(GenerationTaskRequest taskRequest,
                                     CodeGenTypeEnum codeGenType,
                                     GenerationWorkspace workspace,
                                     GenerationModeDecision modeDecision,
                                     GenerationTaskExecution execution) {
        this(taskRequest, codeGenType, workspace, IntentProfile.unknown(), modeDecision, null, execution);
    }

    public boolean modeIs(GenerationMode mode) {
        return modeDecision != null && modeDecision.mode() == mode;
    }

    public GenerationTaskExecution requireExecution() {
        if (execution == null) {
            throw new IllegalStateException("生成任务尚未绑定执行上下文");
        }
        return execution;
    }

    public GenerationPipelineRequest withExecution(GenerationTaskExecution taskExecution) {
        return new GenerationPipelineRequest(
                taskRequest, codeGenType, workspace, intentProfile, modeDecision, executionPlan, taskExecution);
    }

    public GenerationPipelineRequest withExecutionPlan(GenerationExecutionPlan plan) {
        return new GenerationPipelineRequest(
                taskRequest, codeGenType, workspace, intentProfile, modeDecision, plan, execution);
    }

    public GenerationPipelineRequest withModeDecision(GenerationModeDecision decision) {
        GenerationExecutionPlan updatedPlan = executionPlan == null ? null : executionPlan.withRoute(decision);
        return new GenerationPipelineRequest(
                taskRequest, codeGenType, workspace, intentProfile, decision, updatedPlan, execution);
    }

    /**
     * 在保持路由与已冻结 SLA 不变的前提下，替换意图画像与随之重算的执行计划。
     *
     * <p>供意图澄清使用：澄清只允许调整模型档位这类资源分配结论，
     * 不允许改写路由决策，否则会绕过提交阶段已完成的门禁与计费口径。</p>
     */
    public GenerationPipelineRequest withRefinedIntent(IntentProfile refinedProfile,
                                                       GenerationExecutionPlan refinedPlan) {
        if (refinedProfile == null) {
            return this;
        }
        GenerationExecutionPlan planToUse = refinedPlan == null ? executionPlan : refinedPlan;
        if (planToUse != null && !planToUse.route().equals(modeDecision)) {
            throw new IllegalArgumentException("意图澄清不得改变流水线路由决策");
        }
        return new GenerationPipelineRequest(
                taskRequest, codeGenType, workspace, refinedProfile, modeDecision, planToUse, execution);
    }
}