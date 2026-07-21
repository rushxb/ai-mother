package com.rush.rushaicodemother.monitor;

import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/** Records aggregate AI model telemetry with production-safe, bounded dimensions. */
@Component
@RequiredArgsConstructor
public class AiModelMetricsCollector {

    private static final String DEFAULT_PROVIDER = "langchain4j";
    private static final Set<String> REQUEST_STATUSES = Set.of("started", "success", "error");
    private static final Set<String> TOKEN_TYPES = Set.of("input", "output", "total");
    private static final Set<String> CAPACITY_GATES = Set.of(
            "all", "concurrency", "rpm", "tpm", "infrastructure");
    private static final Set<String> CAPACITY_OUTCOMES = Set.of(
            "acquired", "rejected", "bypassed");
    private static final Set<String> CAPACITY_LEASE_OUTCOMES = Set.of(
            "renewed", "retryable_failure", "lost", "max_hold_exceeded", "release_failed");

    private final MeterRegistry meterRegistry;

    /**
     * Identity arguments remain for source compatibility and detailed database traces. They are
     * deliberately excluded from metric tags because user, application and task IDs are unbounded.
     */
    public void recordRequest(String userId, String appId, String taskId, String modelName, String status) {
        recordRequest(DEFAULT_PROVIDER, userId, appId, taskId, modelName, status);
    }

    public void recordRequest(String provider,
                              String userId,
                              String appId,
                              String taskId,
                              String modelName,
                              String status) {
        Counter.builder("ai_model_requests_total")
                .description("AI model request count")
                .tag("provider", normalizeProvider(provider))
                .tag("model_name", normalizeModel(modelName))
                .tag("status", bounded(status, REQUEST_STATUSES))
                .register(meterRegistry)
                .increment();
    }

    public void recordError(String userId, String appId, String taskId, String modelName, String errorMessage) {
        recordError(DEFAULT_PROVIDER, userId, appId, taskId, modelName, errorMessage);
    }

    public void recordError(String provider,
                            String userId,
                            String appId,
                            String taskId,
                            String modelName,
                            String errorMessage) {
        Counter.builder("ai_model_errors_total")
                .description("AI model error count")
                .tag("provider", normalizeProvider(provider))
                .tag("model_name", normalizeModel(modelName))
                .tag("error_category", GenerationErrorClassifier.classify(errorMessage).category())
                .register(meterRegistry)
                .increment();
    }

    public void recordTokenUsage(String userId,
                                 String appId,
                                 String taskId,
                                 String modelName,
                                 String tokenType,
                                 long tokenCount) {
        recordTokenUsage(DEFAULT_PROVIDER, userId, appId, taskId, modelName, tokenType, tokenCount);
    }

    public void recordTokenUsage(String provider,
                                 String userId,
                                 String appId,
                                 String taskId,
                                 String modelName,
                                 String tokenType,
                                 long tokenCount) {
        if (tokenCount <= 0) {
            return;
        }
        Counter.builder("ai_model_tokens_total")
                .description("AI model token usage")
                .tag("provider", normalizeProvider(provider))
                .tag("model_name", normalizeModel(modelName))
                .tag("token_type", bounded(tokenType, TOKEN_TYPES))
                .register(meterRegistry)
                .increment(tokenCount);
    }

    public void recordResponseTime(String userId,
                                   String appId,
                                   String taskId,
                                   String modelName,
                                   Duration duration) {
        recordResponseTime(DEFAULT_PROVIDER, userId, appId, taskId, modelName, duration);
    }

    public void recordResponseTime(String provider,
                                   String userId,
                                   String appId,
                                   String taskId,
                                   String modelName,
                                   Duration duration) {
        Timer.builder("ai_model_response_duration_seconds")
                .description("AI model response duration")
                .tag("provider", normalizeProvider(provider))
                .tag("model_name", normalizeModel(modelName))
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(nonNegative(duration));
    }

    public void recordTracePersistenceFailure(String provider, String modelName, String outcome) {
        Counter.builder("ai_model_trace_persistence_failures_total")
                .description("AI model provenance persistence failure count")
                .tag("provider", normalizeProvider(provider))
                .tag("model_name", normalizeModel(modelName))
                .tag("outcome", bounded(outcome, Set.of("success", "error")))
                .register(meterRegistry)
                .increment();
    }

    public void recordFailover(String fromProvider,
                               String fromModel,
                               String toProvider,
                               String toModel,
                               String errorCategory) {
        Counter.builder("ai_model_failovers_total")
                .description("AI model request-level failover count")
                .tag("from_provider", normalizeProvider(fromProvider))
                .tag("from_model", normalizeModel(fromModel))
                .tag("to_provider", normalizeProvider(toProvider))
                .tag("to_model", normalizeModel(toModel))
                .tag("error_category", normalizeErrorCategory(errorCategory))
                .register(meterRegistry)
                .increment();
    }

    public void recordCapacityAdmission(String provider,
                                        String modelName,
                                        String gate,
                                        String outcome,
                                        Duration duration) {
        String normalizedGate = bounded(gate, CAPACITY_GATES);
        String normalizedOutcome = bounded(outcome, CAPACITY_OUTCOMES);
        Counter.builder("ai_model_capacity_admissions_total")
                .description("AI model cluster-wide capacity admission outcomes")
                .tag("provider", normalizeProvider(provider))
                .tag("model_name", normalizeModel(modelName))
                .tag("gate", normalizedGate)
                .tag("outcome", normalizedOutcome)
                .register(meterRegistry)
                .increment();
        Timer.builder("ai_model_capacity_admission_duration_seconds")
                .description("AI model cluster-wide capacity admission latency")
                .tag("provider", normalizeProvider(provider))
                .tag("model_name", normalizeModel(modelName))
                .tag("outcome", normalizedOutcome)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(nonNegative(duration));
    }

    public void recordCapacityLeaseEvent(String provider,
                                         String modelName,
                                         String outcome) {
        Counter.builder("ai_model_capacity_lease_events_total")
                .description("AI model distributed concurrency lease lifecycle events")
                .tag("provider", normalizeProvider(provider))
                .tag("model_name", normalizeModel(modelName))
                .tag("outcome", bounded(outcome, CAPACITY_LEASE_OUTCOMES))
                .register(meterRegistry)
                .increment();
    }

    private String bounded(String value, Set<String> allowedValues) {
        String normalized = normalize(value);
        return allowedValues.contains(normalized) ? normalized : "unknown";
    }

    private String normalizeModel(String value) {
        String normalized = normalize(value).replaceAll("[^a-z0-9._-]", "_");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private String normalizeProvider(String value) {
        String normalized = normalize(value).replaceAll("[^a-z0-9._-]", "_");
        return normalized.length() <= 40 ? normalized : normalized.substring(0, 40);
    }

    private String normalizeErrorCategory(String value) {
        String normalized = normalize(value).replaceAll("[^a-z0-9._-]", "_");
        return normalized.length() <= 48 ? normalized : normalized.substring(0, 48);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private Duration nonNegative(Duration duration) {
        return duration == null || duration.isNegative() ? Duration.ZERO : duration;
    }
}
