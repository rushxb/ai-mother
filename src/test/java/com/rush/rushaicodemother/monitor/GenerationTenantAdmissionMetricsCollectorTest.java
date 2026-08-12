package com.rush.rushaicodemother.monitor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationTenantAdmissionMetricsCollectorTest {

    @Test
    void metricsMustUseBoundedOutcomeWithoutTenantIdentity() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GenerationTenantAdmissionMetricsCollector collector =
                new GenerationTenantAdmissionMetricsCollector(registry);

        collector.record("tenant_heavy");

        assertEquals(1.0, registry.get("generation_tenant_admission_total")
                .tag("outcome", "tenant_heavy")
                .counter().count());
    }
}
