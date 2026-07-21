package com.rush.rushaicodemother.monitor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DevServerSessionMetricsCollectorTest {

    @Test
    void shouldExposeBoundedDurableSessionMetricsWithoutApplicationIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DevServerSessionMetricsCollector collector = new DevServerSessionMetricsCollector(registry);

        collector.recordClaim("ACQUIRED");
        collector.recordLeaseRenewal("RETRYABLE_FAILURE");
        collector.recordRecovery("container", "success");

        assertEquals(1.0, registry.get("dev_server_session_claims_total")
                .tag("status", "acquired").counter().count());
        assertEquals(1.0, registry.get("dev_server_session_lease_renewals_total")
                .tag("status", "retryable_failure").counter().count());
        assertEquals(1.0, registry.get("dev_server_session_recoveries_total")
                .tag("backend", "container").tag("status", "success").counter().count());
    }
}
