package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;

/** 场景路由没有对应静态 pipeline 能力时的提交前拒绝。 */
public class GenerationPipelineCapabilityException extends BusinessException {

    public GenerationPipelineCapabilityException(GenerationScenarioDecision decision) {
        super(ErrorCode.OPERATION_ERROR, buildMessage(decision));
    }

    private static String buildMessage(GenerationScenarioDecision decision) {
        GenerationPipelineCapabilityKey key = GenerationPipelineCapabilityKey.from(decision);
        return "当前场景没有可执行的生成管线：operation=" + key.operation()
                + ", mutability=" + key.mutability()
                + ", type=" + key.targetType().getValue()
                + ", mode=" + decision.routeDecision().mode();
    }
}
