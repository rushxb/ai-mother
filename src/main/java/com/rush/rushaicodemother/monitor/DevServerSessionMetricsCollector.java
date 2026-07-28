package com.rush.rushaicodemother.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 持久开发服务器所有权的低基数运营指标。 */
@Component
public class DevServerSessionMetricsCollector {

    private static final Set<String> CLAIM_STATUSES = Set.of(
            "acquired", "active_session_exists", "user_quota_exceeded", "error"
    );
    private static final Set<String> LEASE_STATUSES = Set.of(
            "renewed", "stop_requested", "retryable_failure", "lost"
    );
    private static final Set<String> RECOVERY_STATUSES = Set.of(
            "claimed", "success", "cleanup_failed", "terminalize_lost"
    );

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    @Autowired
    public DevServerSessionMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private DevServerSessionMetricsCollector() {
        this.meterRegistry = null;
    }

    public static DevServerSessionMetricsCollector noOp() {
        return new DevServerSessionMetricsCollector();
    }

    /**
 * 记录{@code Claim}相关指标或状态。
 *
 * @param status 目标状态
 */
    public void recordClaim(String status) {
        increment(
                "claim:" + bounded(status, CLAIM_STATUSES),
                "dev_server_session_claims_total",
                "Durable Dev Server session claim outcomes",
                "status",
                bounded(status, CLAIM_STATUSES)
        );
    }

    /**
 * 记录租约{@code Renewal}相关指标或状态。
 *
 * @param status 目标状态
 */
    public void recordLeaseRenewal(String status) {
        increment(
                "lease:" + bounded(status, LEASE_STATUSES),
                "dev_server_session_lease_renewals_total",
                "Durable Dev Server lease renewal outcomes",
                "status",
                bounded(status, LEASE_STATUSES)
        );
    }

    /**
 * 记录恢复相关指标或状态。
 *
 * @param backend 后端
 * @param status 目标状态
 */
    public void recordRecovery(String backend, String status) {
        String normalizedBackend = backend(backend);
        String normalizedStatus = bounded(status, RECOVERY_STATUSES);
        String key = "recovery:" + normalizedBackend + ":" + normalizedStatus;
        if (meterRegistry == null) {
            return;
        }
        counters.computeIfAbsent(key, unused -> Counter.builder("dev_server_session_recoveries_total")
                .description("Expired Dev Server session recovery outcomes")
                .tag("backend", normalizedBackend)
                .tag("status", normalizedStatus)
                .register(meterRegistry)).increment();
    }

    private void increment(String key, String name, String description, String tagName, String tagValue) {
        if (meterRegistry == null) {
            return;
        }
        counters.computeIfAbsent(key, unused -> Counter.builder(name)
                .description(description)
                .tag(tagName, tagValue)
                .register(meterRegistry)).increment();
    }

    private String backend(String value) {
        String normalized = normalize(value);
        return "container".equals(normalized) || "host-local".equals(normalized)
                ? normalized
                : "unknown";
    }

    private String bounded(String value, Set<String> allowed) {
        String normalized = normalize(value);
        return allowed.contains(normalized) ? normalized : "error";
    }

    private String normalize(String value) {
        return value == null || value.isBlank()
                ? "unknown"
                : value.trim().toLowerCase(Locale.ROOT);
    }
}
