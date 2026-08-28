package com.rush.rushaicodemother.orchestration.benchmark;

/** Benchmark 对执行过程中是否发生路由回退的期望。 */
public enum GenerationBenchmarkFallbackExpectation {

    /** 必须观测到回退事实，用于验证降级链路仍可交付。 */
    REQUIRED,

    /** 禁止发生回退，用于验证应保持稳定的直接执行路径。 */
    FORBIDDEN,

    /** 不把回退与否作为该样本的判定条件。 */
    OPTIONAL
}
