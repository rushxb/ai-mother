package com.rush.rushaicodemother.monitor;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AiModelMetricsCollectorTest {

    @Test
    void metricsMustExcludeUnboundedBusinessIdentitiesAndRawErrors() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiModelMetricsCollector collector = new AiModelMetricsCollector(registry);

        collector.recordRequest("user-1", "app-1", "task-1", "GPT-4.1", "success");
        collector.recordError("user-1", "app-1", "task-1", "GPT-4.1",
                "429 provider request abc-123 secret-detail");
        collector.recordTokenUsage("user-1", "app-1", "task-1", "GPT-4.1", "input", 42);
        collector.recordResponseTime("user-1", "app-1", "task-1", "GPT-4.1", Duration.ofSeconds(2));
        collector.recordCapacityAdmission(
                "OpenAI", "GPT-4.1", "concurrency", "rejected", Duration.ofMillis(12));
        collector.recordHedge(
                "OpenAI", "GPT-4.1", "Anthropic", "Claude-Sonnet-4", "hedge_won");
        collector.recordRootModelAttempt(
                "failed", new java.util.concurrent.TimeoutException("模型调用超时"),
                Duration.ofSeconds(2));
        collector.recordRootModelRetry(
                "scheduled", new java.util.concurrent.TimeoutException("模型调用超时"),
                Duration.ofSeconds(3));
        collector.recordModelTimeout("OpenAI", "GPT-4.1", "first-signal");

        Meter errorMeter = registry.find("ai_model_errors_total")
                .tag("error_category", "model_rate_limit")
                .meter();
        assertNotNull(errorMeter);
        Set<String> allTagKeys = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());
        assertFalse(allTagKeys.contains("user_id"));
        assertFalse(allTagKeys.contains("app_id"));
        assertFalse(allTagKeys.contains("task_id"));
        assertFalse(allTagKeys.contains("error_message"));
        assertEquals(1, registry.find("ai_model_requests_total")
                .tag("provider", "langchain4j")
                .tag("model_name", "gpt-4.1")
                .tag("status", "success")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("ai_model_capacity_admissions_total")
                .tag("provider", "openai")
                .tag("model_name", "gpt-4.1")
                .tag("gate", "concurrency")
                .tag("outcome", "rejected")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("ai_model_first_token_hedges_total")
                .tag("primary_provider", "openai")
                .tag("primary_model", "gpt-4.1")
                .tag("hedge_provider", "anthropic")
                .tag("hedge_model", "claude-sonnet-4")
                .tag("outcome", "hedge_won")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("ai_model_root_attempts_total")
                .tag("outcome", "failed")
                .tag("error_category", "model_timeout")
                .counter()
                .count(), 0.001);
        assertEquals(2, registry.find("ai_model_root_attempt_duration_seconds")
                .tag("outcome", "failed")
                .timer()
                .totalTime(java.util.concurrent.TimeUnit.SECONDS), 0.001);
        assertEquals(1, registry.find("ai_model_root_retries_total")
                .tag("outcome", "scheduled")
                .tag("error_category", "model_timeout")
                .counter()
                .count(), 0.001);
        assertEquals(3, registry.find("ai_model_root_retry_delay_seconds")
                .tag("outcome", "scheduled")
                .timer()
                .totalTime(java.util.concurrent.TimeUnit.SECONDS), 0.001);
        assertEquals(1, registry.find("ai_model_timeouts_total")
                .tag("provider", "openai")
                .tag("model_name", "gpt-4.1")
                .tag("timeout_kind", "first-signal")
                .counter()
                .count(), 0.001);
    }

    @Test
    void unexpectedStatusesAndTokenTypesMustCollapseToUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiModelMetricsCollector collector = new AiModelMetricsCollector(registry);

        collector.recordRequest("1", "1", "task", "model", "task-specific-status");
        collector.recordTokenUsage("1", "1", "task", "model", "cache-hit-123", 5);
        collector.recordHedge("p1", "m1", "p2", "m2", "task-specific-outcome");
        collector.recordRootModelAttempt("task-specific-outcome", null, Duration.ZERO);
        collector.recordRootModelRetry("task-specific-outcome", null, Duration.ZERO);
        collector.recordModelTimeout("p1", "m1", "task-specific-timeout");

        assertNotNull(registry.find("ai_model_requests_total").tag("status", "unknown").counter());
        assertNotNull(registry.find("ai_model_tokens_total").tag("token_type", "unknown").counter());
        assertNotNull(registry.find("ai_model_first_token_hedges_total")
                .tag("outcome", "unknown")
                .counter());
        assertNotNull(registry.find("ai_model_root_attempts_total")
                .tag("outcome", "unknown")
                .counter());
        assertNotNull(registry.find("ai_model_root_retries_total")
                .tag("outcome", "unknown")
                .counter());
        assertNotNull(registry.find("ai_model_timeouts_total")
                .tag("timeout_kind", "unknown")
                .counter());
    }
}
