package com.rush.rushaicodemother.orchestration.runtime.task;

/** Result of attempting to admit one durable command into the local worker runtime. */
public enum GenerationTaskDispatchResult {
    SCHEDULED,
    ALREADY_ACTIVE,
    TERMINAL,
    RETRY
}
