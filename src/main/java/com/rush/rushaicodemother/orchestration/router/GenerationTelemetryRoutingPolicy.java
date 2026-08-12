package com.rush.rushaicodemother.orchestration.router;

import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 升级历史上不可靠的应用程序，并在饱和期间包含不明确的工作。 */
@Component
@Order(5)
@RequiredArgsConstructor
public class GenerationTelemetryRoutingPolicy implements GenerationRoutingPolicy {

    private final GenerationRoutingTelemetryProperties properties;

    /**
 * 根据输入信号确定生成遥测路由策略。
 *
 * @param signal 输入信号
 * @return 可选的生成遥测路由策略；不存在时返回空值
 */
    @Override
    public Optional<GenerationModeDecision> decide(GenerationRoutingSignal signal) {
        GenerationRoutingTelemetrySnapshot telemetry = signal.telemetry();
        if (!signal.existingWorkspace() || telemetry == null || !telemetry.available()) {
            return Optional.empty();
        }
        // 应用级历史质量只能保护复杂请求，不能把当前明确的轻量编辑升级到 Heavy。
        if (isClearlyLightweight(signal.intentProfile())) {
            return Optional.empty();
        }
        if (hasQualityRisk(telemetry)) {
            return Optional.of(GenerationModeDecision.of(
                    GenerationMode.HEAVY_EXPERT,
                    0.78,
                    "历史任务失败率或明确低评分较高，采用质量优先的专家模式",
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
                    "生成容量已饱和且历史耗时较高，采用有界智能体编辑模式",
                    FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                    ExpectedValidationLevel.BUILD,
                    GenerationRoutingDecisionCode.TELEMETRY_SATURATION_CONTAINMENT
            ));
        }
        return Optional.empty();
    }

    private boolean isClearlyLightweight(IntentProfile profile) {
        return profile != null
                && profile.semanticComplexity()
                == com.rush.rushaicodemother.orchestration.intent.IntentSemanticComplexity.LOW
                && profile.validationRisk()
                == com.rush.rushaicodemother.orchestration.intent.IntentValidationRisk.LOW
                && !profile.requiresBackend()
                && !profile.requiresDatabase()
                && profile.expectedFileCount() <= 2;
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
