package com.rush.rushaicodemother.orchestration.readonly;

/** 只读请求的结构化完成状态，避免把“无项目可分析”伪装成模型结论。 */
public enum ReadOnlyAnalysisStatus {
    COMPLETED,
    NO_PROJECT_CONTEXT,
    NOT_AUDITABLE
}
