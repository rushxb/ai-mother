package com.rush.rushaicodemother.orchestration.router;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Escalates historically unreliable applications and contains ambiguous work during saturation. */
@Component
@Order(35)
@RequiredArgsConstructor
public class GenerationTelemetryRoutingPolicy implements GenerationRoutingPolicy {

    private final GenerationRoutingTelemetryProperties properties;

    @Override
    public Optional<GenerationModeDecision> decide(GenerationRoutingSignal signal) {
        GenerationRoutingTelemetrySnapshot telemetry = signal.telemetry();
        if (!signal.existingWorkspace() || telemetry == null || !telemetry.available()) {
            return Optional.empty();
        }
        if (hasQualityRisk(telemetry)) {
            return Optional.of(GenerationModeDecision.of(
                    GenerationMode.HEAVY_EXPERT,
                    0.78,
                    "Historical failures or explicit low ratings require quality-first expert routing",
                    FallbackPolicy.NONE,
                    ExpectedValidationLevel.EXPERT,
                    GenerationRoutingDecisionCode.TELEMETRY_QUALITY_ESCALATION
            ));
        }
        if (isSaturated(telemetry)
                && telemetry.averageDurationMs() >= properties.getSlowAverageDuration().toMillis()
                && signal.normalizedMessage().length() > 160) {
            return Optional.of(GenerationModeDecision.of(
                    GenerationMode.AGENT_EDIT,
                    0.72,
                    "Generation capacity is saturated and historical duration is high; use bounded agent edit mode",
                    FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                    ExpectedValidationLevel.BUILD,
                    GenerationRoutingDecisionCode.TELEMETRY_SATURATION_CONTAINMENT
            ));
        }
        return Optional.empty();
    }

    private boolean hasQualityRisk(GenerationRoutingTelemetrySnapshot telemetry) {
        boolean taskRisk = telemetry.recentTaskCount() >= properties.getMinimumTaskSamples()
                && telemetry.failureRate() >= properties.getHighFailureRate();
        boolean feedbackRisk = telemetry.feedbackCount() >= properties.getMinimumFeedbackSamples()
                && telemetry.lowRatingRate() >= properties.getHighLowRatingRate();
        return taskRisk || feedbackRisk;
    }

    private boolean isSaturated(GenerationRoutingTelemetrySnapshot telemetry) {
        return telemetry.runningPressure() >= properties.getHighLoadRatio()
                || telemetry.queuePressure() >= properties.getHighLoadRatio();
    }
}
