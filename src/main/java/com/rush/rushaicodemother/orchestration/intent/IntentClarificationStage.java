package com.rush.rushaicodemother.orchestration.intent;

import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlanner;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 兼容历史流水线组装的退役意图澄清阶段。
 *
 * <p>场景澄清已迁移到拥有 task identity、独立预算和费用门禁的
 * {@code GenerationScenarioPreflight}。worker 只消费已冻结决策，不再重新解释用户输入。</p>
 */
@Component
public class IntentClarificationStage {

    /**
     * 创建意图澄清阶段。
     *
     * @param clarificationRefiner 意图澄清精化器
     * @param executionPlanner 执行计划编排器
     */
    public IntentClarificationStage(IntentClarificationRefiner clarificationRefiner,
                                    GenerationExecutionPlanner executionPlanner) {
        Objects.requireNonNull(clarificationRefiner, "意图澄清精化器不能为空");
        Objects.requireNonNull(executionPlanner, "执行计划编排器不能为空");
    }

    /**
     * 返回提交阶段已冻结的原请求。
     *
     * @param request 任务运行时的流水线请求
     * @return 原请求
     */
    public GenerationPipelineRequest apply(GenerationPipelineRequest request) {
        return request;
    }
}
