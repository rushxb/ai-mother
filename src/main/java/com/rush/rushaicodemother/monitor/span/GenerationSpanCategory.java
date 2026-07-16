package com.rush.rushaicodemother.monitor.span;

/** Stable categories used to aggregate generation critical-path latency. */
public enum GenerationSpanCategory {
    QUEUE,
    PIPELINE,
    MODEL,
    TOOL,
    DEPENDENCY,
    BUILD,
    VALIDATION,
    REPAIR,
    FINALIZATION
}
