package com.rush.rushaicodemother.orchestration.router;

/** 执行策略和遥测使用的稳定的机器可读原因。 */
public enum GenerationRoutingDecisionCode {

    CREATE_TEMPLATE_COVERAGE_GAP,
    CREATE_TEMPLATE_FIRST,
    EXPLICIT_HEAVY_EXPERT,
    TELEMETRY_QUALITY_ESCALATION,
    TELEMETRY_SATURATION_CONTAINMENT,
    AGENT_EDIT_COMPLEXITY,
    LIGHT_EDIT_SCOPE,
    DEFAULT_AGENT_EDIT,
    FALLBACK_HEAVY_EXPERT,
    UNKNOWN
}
