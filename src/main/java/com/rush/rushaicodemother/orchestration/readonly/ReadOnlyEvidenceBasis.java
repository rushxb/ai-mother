package com.rush.rushaicodemother.orchestration.readonly;

/** 只读结论实际依赖的权威事实类型。 */
public enum ReadOnlyEvidenceBasis {
    REPOSITORY_FACTS,
    REPOSITORY_AND_REQUIREMENT,
    USER_REQUIREMENT,
    NO_REPOSITORY_CONTEXT
}
