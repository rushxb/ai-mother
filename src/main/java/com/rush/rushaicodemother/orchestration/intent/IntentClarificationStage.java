package com.rush.rushaicodemother.orchestration.intent;

import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlanner;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 任务运行时的意图澄清阶段。
 *
 * <p>该阶段刻意放在任务线程而非提交线程：提交线程没有 taskId、没有执行预算，
 * 在那里调模型等于开一条不受额度约束的成本口子。任务线程上澄清调用与其他模型调用
 * 共用同一份预算，超时也受任务截止时间约束。</p>
 *
 * <p>澄清的采纳范围被压到最小：只重算模型档位。SLA、验证图、写工具预算和计费口径
 * 都在提交阶段冻结，任务运行期改写它们会绕过已经通过的门禁。</p>
 */
@Component
public class IntentClarificationStage {

    private final IntentClarificationRefiner clarificationRefiner;
    private final GenerationExecutionPlanner executionPlanner;

    /**
     * 创建意图澄清阶段。
     *
     * @param clarificationRefiner 意图澄清精化器
     * @param executionPlanner 执行计划编排器
     */
    public IntentClarificationStage(IntentClarificationRefiner clarificationRefiner,
                                    GenerationExecutionPlanner executionPlanner) {
        this.clarificationRefiner = Objects.requireNonNull(
                clarificationRefiner, "意图澄清精化器不能为空");
        this.executionPlanner = Objects.requireNonNull(
                executionPlanner, "执行计划编排器不能为空");
    }

    /**
     * 在需要时澄清意图，并返回携带精化画像与重算模型档位的请求。
     *
     * @param request 任务运行时的流水线请求
     * @return 精化后的请求；无需澄清或澄清失败时返回原请求
     */
    public GenerationPipelineRequest apply(GenerationPipelineRequest request) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (request == null
                || request.modeIs(GenerationMode.READ_ONLY)
                || request.execution() == null
                || request.taskRequest() == null) {
            return request;
        }
        GenerationTaskExecution execution = request.execution();
        IntentProfile refinedProfile = clarificationRefiner.refine(
                request.intentProfile(),
                request.taskRequest().message(),
                execution.taskId(),
                execution.executionContext());
        if (refinedProfile == null || refinedProfile.equals(request.intentProfile())) {
            return request;
        }
        GenerationExecutionPlan refinedPlan = executionPlanner.replanModelProfile(
                request.executionPlan(), refinedProfile, request.codeGenType());
        return request.withRefinedIntent(refinedProfile, refinedPlan);
    }
}
