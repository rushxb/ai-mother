package com.rush.rushaicodemother.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 记录低基数的租户生成准入结果。 */
@Component
public class GenerationTenantAdmissionMetricsCollector {

    private static final Set<String> OUTCOMES = Set.of(
            "tenant_tasks", "tenant_heavy", "monthly_budget");

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    @Autowired
    public GenerationTenantAdmissionMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private GenerationTenantAdmissionMetricsCollector() {
        this.meterRegistry = null;
    }

    public static GenerationTenantAdmissionMetricsCollector noOp() {
        return new GenerationTenantAdmissionMetricsCollector();
    }

    public void record(String outcome) {
        if (meterRegistry == null) {
            return;
        }
        String boundedOutcome = OUTCOMES.contains(outcome) ? outcome : "monthly_budget";
        counters.computeIfAbsent(boundedOutcome, ignored -> Counter.builder(
                        "generation_tenant_admission_total")
                .description("租户级生成任务准入结果")
                .tag("outcome", boundedOutcome)
                .register(meterRegistry)).increment();
    }
}
