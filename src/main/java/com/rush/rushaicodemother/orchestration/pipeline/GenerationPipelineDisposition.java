package com.rush.rushaicodemother.orchestration.pipeline;

/** Describes who owns task completion after a pipeline invocation returns. */
public enum GenerationPipelineDisposition {
    COMPLETED,
    RUNNING,
    FALLBACK
}
