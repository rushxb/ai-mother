package com.rush.rushaicodemother.orchestration.runtime.execution;

/**
 * 由于执行策略已用尽而无法继续的生成任务的基本异常。
 */
public class GenerationExecutionPolicyException extends RuntimeException {

    public GenerationExecutionPolicyException(String message) {
        super(message);
    }
}
