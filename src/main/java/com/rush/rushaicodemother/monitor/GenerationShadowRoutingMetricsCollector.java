package com.rush.rushaicodemother.monitor;

import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.learning.GenerationShadowRoutingComparison;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 记录不包含原始提示词和业务标识的影子路由对比指标。 */
@Component
public class GenerationShadowRoutingMetricsCollector {

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    @Autowired
    public GenerationShadowRoutingMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private GenerationShadowRoutingMetricsCollector() {
        this.meterRegistry = null;
    }

    public static GenerationShadowRoutingMetricsCollector noOp() {
        return new GenerationShadowRoutingMetricsCollector();
    }

    public void recordComparison(GenerationShadowRoutingComparison comparison) {
        if (comparison == null) {
            return;
        }
        record(
                "success",
                comparison.intentProfile(),
                comparison.champion(),
                comparison.challenger(),
                comparison.agreement()
        );
    }

    public void recordFailure(IntentProfile intentProfile, GenerationModeDecision champion) {
        record("failed", intentProfile, champion, null, false);
    }

    private void record(String status,
                        IntentProfile intentProfile,
                        GenerationModeDecision champion,
                        GenerationModeDecision challenger,
                        boolean agreement) {
        if (meterRegistry == null) {
            return;
        }
        String championRoute = route(champion);
        String championCode = decisionCode(champion);
        String challengerRoute = route(challenger);
        String challengerCode = decisionCode(challenger);
        String operationType = intentProfile == null
                ? UNKNOWN : intentProfile.operationType().name().toLowerCase(Locale.ROOT);
        String complexity = intentProfile == null
                ? UNKNOWN : intentProfile.semanticComplexity().name().toLowerCase(Locale.ROOT);
        String agreementTag = Boolean.toString(agreement);
        String key = String.join(":", status, championRoute, championCode,
                challengerRoute, challengerCode, operationType, complexity, agreementTag);
        counters.computeIfAbsent(key, ignored -> Counter.builder("generation_routing_shadow_decisions_total")
                .description("生成模式 Champion 与 Challenger 影子路由对比次数")
                .tag("status", status)
                .tag("champion_route", championRoute)
                .tag("champion_code", championCode)
                .tag("challenger_route", challengerRoute)
                .tag("challenger_code", challengerCode)
                .tag("operation_type", operationType)
                .tag("semantic_complexity", complexity)
                .tag("agreement", agreementTag)
                .register(meterRegistry)).increment();
    }

    private String route(GenerationModeDecision decision) {
        return decision == null ? UNKNOWN : decision.route();
    }

    private String decisionCode(GenerationModeDecision decision) {
        return decision == null ? UNKNOWN : decision.decisionCode().name().toLowerCase(Locale.ROOT);
    }
}
