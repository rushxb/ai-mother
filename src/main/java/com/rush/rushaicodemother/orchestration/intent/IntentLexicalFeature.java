package com.rush.rushaicodemother.orchestration.intent;

/**
 * 可被低成本词法规则识别的意图特征。
 *
 * <p>枚举只表达“要识别什么”，具体词表、词边界和否定语义均由
 * {@link IntentLexicalRuleSet} 统一管理，避免业务服务散落字符串判断。</p>
 */
enum IntentLexicalFeature {
    REPAIR_ACTION,
    REPAIR_SYMPTOM,
    EXPLANATION_ACTION,
    AUDIT_ACTION,
    PLAN_ACTION,
    READ_ONLY_CONSTRAINT,
    EDIT_ACTION,
    LIGHT_EDIT,
    FRONTEND,
    BACKEND,
    API,
    DATABASE,
    AUTHENTICATION,
    BUILD_CONFIGURATION,
    INFRASTRUCTURE,
    TESTING,
    DOCUMENTATION,
    FULL_STACK_PROJECT,
    ENGINEERED_FRONTEND_PROJECT,
    MULTI_FILE_PROJECT,
    SINGLE_HTML_PROJECT,
    MULTI_FILE,
    SINGLE_FILE,
    HIGH_COMPLEXITY,
    HIGH_DESTRUCTIVE_RISK,
    MEDIUM_DESTRUCTIVE_RISK
}
