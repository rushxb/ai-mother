package com.rush.rushaicodemother.orchestration.runtime.execution;

/**
 * Base exception for a generation task that cannot continue because its execution policy was exhausted.
 */
public class GenerationExecutionPolicyException extends RuntimeException {

    public GenerationExecutionPolicyException(String message) {
        super(message);
    }
}
