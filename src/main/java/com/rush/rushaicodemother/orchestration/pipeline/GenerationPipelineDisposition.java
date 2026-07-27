package com.rush.rushaicodemother.orchestration.pipeline;

/** 描述管道调用返回后谁拥有任务完成权。 */
public enum GenerationPipelineDisposition {
    COMPLETED,
    RUNNING,
    FALLBACK
}
