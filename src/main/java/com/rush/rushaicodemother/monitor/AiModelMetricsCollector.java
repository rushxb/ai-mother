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
import java.util.concurrent.atomic.AtomicLong;

/** 记录具有生产安全、有界维度的聚合 AI 模型遥测数据。 */
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
    private static final Set<String> HEDGE_OUTCOMES = Set.of(
            "started", "primary_won", "hedge_won", "failed", "cancelled");
    private static final Set<String> ROOT_ATTEMPT_OUTCOMES = Set.of(
            "success", "failed", "cancelled");
    private static final Set<String> ROOT_RETRY_OUTCOMES = Set.of(
            "scheduled", "recovered", "failed", "cancelled", "exhausted",
            "skipped_non_retriable", "skipped_budget", "skipped_deadline");
    private static final Set<String> MODEL_TIMEOUT_KINDS = Set.of(
            "first-signal", "inactivity", "wall-clock");
    private static final Set<String> USAGE_SOURCES = Set.of(
            "official", "estimated", "unavailable");
    private static final Set<String> INVOCATION_RECOVERY_OUTCOMES = Set.of(
            "success", "failure");

    private final MeterRegistry meterRegistry;
    private final AtomicLong unsettledInvocationCount = new AtomicLong();

    /**
     * 保留身份参数是为了源兼容性和详细的数据库跟踪。他们是
     * 故意从指标标签中排除，因为用户、应用程序和任务 ID 是无限的。
     */
    public void recordRequest(String userId, String appId, String taskId, String modelName, String status) {
        recordRequest(DEFAULT_PROVIDER, userId, appId, taskId, modelName, status);
    }

    /**
 * 记录请求相关指标或状态。
 *
 * @param provider 提供方
 * @param userId 用户编号
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param modelName 模型名称
 * @param status 目标状态
 */
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

    /**
 * 记录错误相关指标或状态。
 *
 * @param provider 提供方
 * @param userId 用户编号
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param modelName 模型名称
 * @param errorMessage 错误消息
 */
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

    /**
 * 记录令牌用量相关指标或状态。
 *
 * @param provider 提供方
 * @param userId 用户编号
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param modelName 模型名称
 * @param tokenType 令牌类型
 * @param tokenCount 令牌数量
 */
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

    /**
 * 记录响应时间相关指标或状态。
 *
 * @param provider 提供方
 * @param userId 用户编号
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param modelName 模型名称
 * @param duration 目标时长
 */
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

    /**
 * 记录追踪持久化失败相关指标或状态。
 *
 * @param provider 提供方
 * @param modelName 模型名称
 * @param outcome 结果
 */
    public void recordTracePersistenceFailure(String provider, String modelName, String outcome) {
        Counter.builder("ai_model_trace_persistence_failures_total")
                .description("AI model provenance persistence failure count")
                .tag("provider", normalizeProvider(provider))
                .tag("model_name", normalizeModel(modelName))
                .tag("outcome", bounded(outcome, Set.of("started", "success", "error")))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录终态物理调用的 token 事实来源。estimated/unavailable 占全部来源的比例即 missing usage rate。
     */
    public void recordUsageResolution(String provider, String modelName, String source) {
        Counter.builder("ai_model_usage_resolution_total")
                .description("AI model terminal invocation usage resolution source")
                .tag("provider", normalizeProvider(provider))
                .tag("model_name", normalizeModel(modelName))
                .tag("source", bounded(source, USAGE_SOURCES))
                .register(meterRegistry)
                .increment();
    }

    /** 记录恢复的遗留物理调用数量；amount 必须是实际受影响行数。 */
    public void recordInvocationRecovery(String outcome, long amount) {
        if (amount <= 0) {
            return;
        }
        Counter.builder("ai_model_invocation_recoveries_total")
                .description("Recovered or failed stale AI model invocation ledger rows")
                .tag("outcome", bounded(outcome, INVOCATION_RECOVERY_OUTCOMES))
                .register(meterRegistry)
                .increment(amount);
    }

    /** 使用数据库事实刷新未结算 STARTED 调用数量，而不是从进程内事件推算。 */
    public void recordUnsettledInvocationCount(long count) {
        unsettledInvocationCount.set(Math.max(0L, count));
        io.micrometer.core.instrument.Gauge
                .builder("ai_model_unsettled_invocation_count", unsettledInvocationCount,
                        AtomicLong::doubleValue)
                .description("Current durable STARTED AI model invocation ledger rows")
                .register(meterRegistry);
    }

    /**
 * 记录故障转移相关指标或状态。
 *
 * @param fromProvider {@code fromProvider} 对应的调用参数
 * @param fromModel {@code fromModel} 对应的调用参数
 * @param toProvider {@code toProvider} 对应的调用参数
 * @param toModel {@code toModel} 对应的调用参数
 * @param errorCategory {@code errorCategory} 对应的调用参数
 */
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

    /**
 * 记录{@code Hedge}相关指标或状态。
 *
 * @param primaryProvider {@code primaryProvider} 对应的调用参数
 * @param primaryModel {@code primaryModel} 对应的调用参数
 * @param hedgeProvider {@code hedgeProvider} 对应的调用参数
 * @param hedgeModel {@code hedgeModel} 对应的调用参数
 * @param outcome 结果
 */
    public void recordHedge(String primaryProvider,
                            String primaryModel,
                            String hedgeProvider,
                            String hedgeModel,
                            String outcome) {
        Counter.builder("ai_model_first_token_hedges_total")
                .description("AI 模型首 Token 对冲生命周期计数")
                .tag("primary_provider", normalizeProvider(primaryProvider))
                .tag("primary_model", normalizeModel(primaryModel))
                .tag("hedge_provider", normalizeProvider(hedgeProvider))
                .tag("hedge_model", normalizeModel(hedgeModel))
                .tag("outcome", bounded(outcome, HEDGE_OUTCOMES))
                .register(meterRegistry)
                .increment();
    }

    /**
 * 记录根模型尝试相关指标或状态。
 *
 * @param outcome 结果
 * @param failure 失败
 * @param duration 目标时长
 */
    public void recordRootModelAttempt(String outcome,
                                       Throwable failure,
                                       Duration duration) {
        String normalizedOutcome = bounded(outcome, ROOT_ATTEMPT_OUTCOMES);
        String errorCategory = errorCategory(failure);
        Counter.builder("ai_model_root_attempts_total")
                .description("根模型尝试结果计数")
                .tag("outcome", normalizedOutcome)
                .tag("error_category", errorCategory)
                .register(meterRegistry)
                .increment();
        Timer.builder("ai_model_root_attempt_duration_seconds")
                .description("根模型尝试耗时")
                .tag("outcome", normalizedOutcome)
                .tag("error_category", errorCategory)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(nonNegative(duration));
    }

    /**
 * 记录根模型重试相关指标或状态。
 *
 * @param outcome 结果
 * @param failure 失败
 * @param delay 延迟
 */
    public void recordRootModelRetry(String outcome,
                                     Throwable failure,
                                     Duration delay) {
        String normalizedOutcome = bounded(outcome, ROOT_RETRY_OUTCOMES);
        String errorCategory = errorCategory(failure);
        Counter.builder("ai_model_root_retries_total")
                .description("根模型重试生命周期结果计数")
                .tag("outcome", normalizedOutcome)
                .tag("error_category", errorCategory)
                .register(meterRegistry)
                .increment();
        Timer.builder("ai_model_root_retry_delay_seconds")
                .description("根模型重试退避耗时")
                .tag("outcome", normalizedOutcome)
                .tag("error_category", errorCategory)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(nonNegative(delay));
    }

    /**
 * 记录模型超时相关指标或状态。
 *
 * @param provider 提供方
 * @param modelName 模型名称
 * @param timeoutKind 超时类别
 */
    public void recordModelTimeout(String provider,
                                   String modelName,
                                   String timeoutKind) {
        Counter.builder("ai_model_timeouts_total")
                .description("AI 模型监督器判定的超时次数")
                .tag("provider", normalizeProvider(provider))
                .tag("model_name", normalizeModel(modelName))
                .tag("timeout_kind", bounded(timeoutKind, MODEL_TIMEOUT_KINDS))
                .register(meterRegistry)
                .increment();
    }

    /**
 * 记录容量准入相关指标或状态。
 *
 * @param provider 提供方
 * @param modelName 模型名称
 * @param gate 门禁
 * @param outcome 结果
 * @param duration 目标时长
 */
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

    /**
 * 记录容量租约事件相关指标或状态。
 *
 * @param provider 提供方
 * @param modelName 模型名称
 * @param outcome 结果
 */
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

    private String errorCategory(Throwable failure) {
        return failure == null
                ? "none"
                : normalizeErrorCategory(GenerationErrorClassifier.classify(failure).category());
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
