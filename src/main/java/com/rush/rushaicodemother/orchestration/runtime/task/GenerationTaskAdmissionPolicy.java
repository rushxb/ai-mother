package com.rush.rushaicodemother.orchestration.runtime.task;

/** 可独立扩展的生成任务准入规则。 */
public interface GenerationTaskAdmissionPolicy {

    void assertMayAdmit(GenerationTaskAdmissionContext context);

    /** 澄清前的保守只读门禁；默认无附加约束，扩展策略可独立参与。 */
    default void assertMayPreflight(GenerationTaskPreflightAdmissionContext context) {
    }
}
