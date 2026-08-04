package com.rush.rushaicodemother.orchestration.learning;

import com.rush.rushaicodemother.monitor.GenerationShadowRoutingMetricsCollector;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * 在不改变主路由结果的前提下执行 Challenger 并记录有限维度对比指标。
 */
@Service
@RequiredArgsConstructor
public class GenerationShadowRoutingService {

    private final GenerationShadowRoutingProperties properties;
    private final GenerationShadowRouteChallenger challenger;
    private final GenerationShadowRoutingMetricsCollector metricsCollector;

    public Optional<GenerationShadowRoutingComparison> evaluate(IntentProfile intentProfile,
                                                                 GenerationModeDecision champion) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        Objects.requireNonNull(intentProfile, "意图画像不能为空");
        Objects.requireNonNull(champion, "主路由决策不能为空");
        try {
            GenerationModeDecision challengerDecision = Objects.requireNonNull(
                    challenger.decide(intentProfile), "候选路由决策不能为空");
            GenerationShadowRoutingComparison comparison = new GenerationShadowRoutingComparison(
                    intentProfile,
                    champion,
                    challengerDecision
            );
            recordComparisonSafely(comparison);
            return Optional.of(comparison);
        } catch (RuntimeException exception) {
            // 影子评估只承担观测职责，任何故障都不得改变主请求的路由与提交流程。
            recordFailureSafely(intentProfile, champion);
            return Optional.empty();
        }
    }

    private void recordComparisonSafely(GenerationShadowRoutingComparison comparison) {
        try {
            metricsCollector.recordComparison(comparison);
        } catch (RuntimeException ignored) {
            // 指标系统异常不得反向影响影子评估或主请求。
        }
    }

    private void recordFailureSafely(IntentProfile intentProfile, GenerationModeDecision champion) {
        try {
            metricsCollector.recordFailure(intentProfile, champion);
        } catch (RuntimeException ignored) {
            // 指标系统异常不得反向影响主请求。
        }
    }
}
