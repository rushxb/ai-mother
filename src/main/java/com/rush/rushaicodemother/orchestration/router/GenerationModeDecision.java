package com.rush.rushaicodemother.orchestration.router;

import cn.hutool.core.util.StrUtil;

/**
 * 生成模式决策的不可变数据载体。
 */
public record GenerationModeDecision(
        GenerationMode mode,
        double confidence,
        String reason,
        FallbackPolicy fallbackPolicy,
        ExpectedValidationLevel expectedValidationLevel,
        String fallbackReason,
        GenerationRoutingDecisionCode decisionCode
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
        if (decisionCode == null) {
            decisionCode = GenerationRoutingDecisionCode.UNKNOWN;
        }
        confidence = Math.max(0, Math.min(1, confidence));
        reason = StrUtil.blankToDefault(reason, "router_reason_unknown");
        fallbackReason = StrUtil.blankToDefault(fallbackReason, "");
    }

    public GenerationModeDecision(GenerationMode mode,
                                  double confidence,
                                  String reason,
                                  FallbackPolicy fallbackPolicy,
                                  ExpectedValidationLevel expectedValidationLevel,
                                  String fallbackReason) {
        this(mode, confidence, reason, fallbackPolicy, expectedValidationLevel, fallbackReason,
                GenerationRoutingDecisionCode.UNKNOWN);
    }

    public static GenerationModeDecision of(GenerationMode mode,
                                            double confidence,
                                            String reason,
                                            FallbackPolicy fallbackPolicy,
                                            ExpectedValidationLevel validationLevel) {
        return new GenerationModeDecision(mode, confidence, reason, fallbackPolicy, validationLevel, "");
    }

    public static GenerationModeDecision of(GenerationMode mode,
                                            double confidence,
                                            String reason,
                                            FallbackPolicy fallbackPolicy,
                                            ExpectedValidationLevel validationLevel,
                                            GenerationRoutingDecisionCode decisionCode) {
        return new GenerationModeDecision(
                mode, confidence, reason, fallbackPolicy, validationLevel, "", decisionCode);
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
                reason,
                GenerationRoutingDecisionCode.FALLBACK_HEAVY_EXPERT
        );
    }
}
