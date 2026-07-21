package com.rush.rushaicodemother.monitor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneratedCodeSandboxMetricsCollectorTest {

    @Test
    void shouldRecordLowCardinalityExecutionCleanupAndReadinessMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GeneratedCodeSandboxMetricsCollector collector =
                new GeneratedCodeSandboxMetricsCollector(registry);

        collector.recordExecution(
                "container",
                "project-command",
                "COMPLETED",
                Duration.ofMillis(250)
        );
        collector.recordCleanup("container", "success");
        collector.recordReadiness("dev_server_network_policy", "ready");
        collector.recordExecution(
                "unexpected-backend-id",
                "tenant-controlled-workload",
                "failed",
                Duration.ZERO
        );

        assertEquals(1, registry.find("generated_code_sandbox_executions_total")
                .tag("backend", "container")
                .tag("workload", "project-command")
                .tag("status", "completed")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("generated_code_sandbox_execution_duration_seconds")
                .tag("backend", "container")
                .tag("workload", "project-command")
                .tag("status", "completed")
                .timer()
                .count());
        assertEquals(1, registry.find("generated_code_sandbox_cleanup_total")
                .tag("backend", "container")
                .tag("status", "success")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("generated_code_sandbox_readiness_total")
                .tag("resource", "dev_server_network_policy")
                .tag("status", "ready")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("generated_code_sandbox_executions_total")
                .tag("backend", "unknown")
                .tag("workload", "other")
                .tag("status", "failed")
                .counter()
                .count(), 0.001);
    }
}
