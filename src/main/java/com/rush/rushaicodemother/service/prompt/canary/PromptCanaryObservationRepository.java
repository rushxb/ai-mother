package com.rush.rushaicodemother.service.prompt.canary;

/** 从结构化模型调用归因和终态任务事实聚合 Prompt 灰度证据。 */
public interface PromptCanaryObservationRepository {

    PromptCanaryObservation observe(PromptCanaryEvaluationRequest request);
}
