package com.rush.rushaicodemother.orchestration.runtime.task;

/** 可独立扩展的生成任务准入规则。 */
public interface GenerationTaskAdmissionPolicy {

    void assertMayAdmit(GenerationTaskAdmissionContext context);
}
