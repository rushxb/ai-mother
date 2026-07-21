package com.rush.rushaicodemother.ai.tools;

/** Maximum side-effect class of an AI tool exposed to autonomous generation. */
public enum ToolRiskLevel {
    READ_ONLY,
    WRITE,
    DESTRUCTIVE,
    EXTERNAL_SIDE_EFFECT
}
