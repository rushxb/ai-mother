package com.rush.rushaicodemother.orchestration.intent;

/** 用户请求可能影响的稳定功能范围。 */
public enum IntentAffectedScope {
    FRONTEND,
    BACKEND,
    API,
    DATABASE,
    AUTHENTICATION,
    BUILD_CONFIGURATION,
    INFRASTRUCTURE,
    TESTING,
    DOCUMENTATION,
    UNKNOWN
}
