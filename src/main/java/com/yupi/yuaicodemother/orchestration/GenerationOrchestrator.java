package com.yupi.yuaicodemother.orchestration;

/**
 * 生成编排器：负责把用户需求转换为可执行生成计划。
 */
public interface GenerationOrchestrator {

    GenerationOrchestrationResult prepare(GenerationOrchestrationRequest request);
}
