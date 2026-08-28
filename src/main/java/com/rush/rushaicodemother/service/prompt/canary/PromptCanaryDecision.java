package com.rush.rushaicodemother.service.prompt.canary;

/** Prompt 灰度生产证据的门禁结论。 */
public enum PromptCanaryDecision {
    /** 样本或必要观测尚不完整，不能晋级，也不能据此自动回滚。 */
    OBSERVING,
    /** 候选无回归但尚未证明收益，继续保持当前灰度比例。 */
    HOLD,
    /** 候选通过质量、尾延迟、成本与容量联合门禁。 */
    PROMOTABLE,
    /** 可比较样本已证明候选回归，应进入自动回滚流程。 */
    ROLLBACK_REQUIRED,
    /** 归因身份或数据结构不一致，禁止自动作出发布变更。 */
    INVALID
}
