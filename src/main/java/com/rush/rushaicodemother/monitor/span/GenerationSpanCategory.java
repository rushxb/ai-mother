package com.rush.rushaicodemother.monitor.span;

/** 用于聚合生成关键路径延迟的稳定类别。 */
public enum GenerationSpanCategory {
    QUEUE,
    WORKSPACE,
    PIPELINE,
    MODEL,
    TOOL,
    DEPENDENCY,
    BUILD,
    VALIDATION,
    REPAIR,
    FINALIZATION
}
