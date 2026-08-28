package com.rush.rushaicodemother.service.prompt.canary;

/** Prompt 灰度评估不可变事实的写入端口。 */
public interface PromptCanaryAssessmentStore {

    void save(PromptCanaryAssessment assessment);
}
