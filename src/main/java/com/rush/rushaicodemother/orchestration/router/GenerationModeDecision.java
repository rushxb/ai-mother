package com.rush.rushaicodemother.orchestration.router;

import cn.hutool.core.util.StrUtil;

public record GenerationModeDecision(
        GenerationMode mode,
        double confidence,
        String reason,
        FallbackPolicy fallbackPolicy,
        ExpectedValidationLevel expectedValidationLevel,
        String fallbackReason
) {

    public GenerationModeDecision {
        if (mode == null) {
            mode = GenerationMode.HEAVY_EXPERT;
        }
        if (fallbackPolicy == null) {
            fallbackPolicy = FallbackPolicy.NONE;
        }
        if (expectedValidationLevel == null) {
            expectedValidationLevel = ExpectedValidationLevel.BUILD;
        }
        confidence = Math.max(0, Math.min(1, confidence));
        reason = StrUtil.blankToDefault(reason, "router_reason_unknown");
        fallbackReason = StrUtil.blankToDefault(fallbackReason, "");
    }

    public static GenerationModeDecision of(GenerationMode mode,
                                            double confidence,
                                            String reason,
                                            FallbackPolicy fallbackPolicy,
                                            ExpectedValidationLevel validationLevel) {
        return new GenerationModeDecision(mode, confidence, reason, fallbackPolicy, validationLevel, "");
    }

    public String route() {
        return mode.route();
    }

    public GenerationModeDecision withFallback(GenerationMode fallbackMode, String reason) {
        return new GenerationModeDecision(
                fallbackMode,
                Math.min(confidence, 0.5),
                this.reason,
                FallbackPolicy.NONE,
                ExpectedValidationLevel.EXPERT,
                reason
        );
    }
}
