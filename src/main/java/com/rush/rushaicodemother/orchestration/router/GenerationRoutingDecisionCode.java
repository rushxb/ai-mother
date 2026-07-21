package com.rush.rushaicodemother.orchestration.router;

/** Stable machine-readable reason used by execution policy and telemetry. */
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
