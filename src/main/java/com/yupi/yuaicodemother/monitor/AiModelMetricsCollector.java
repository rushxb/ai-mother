package com.yupi.yuaicodemother.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 指标收集器
 */
@Component
@Slf4j
public class AiModelMetricsCollector {

    @Resource
    private MeterRegistry meterRegistry;

    // 缓存已创建的指标，避免重复创建（按指标类型分离缓存）
    private final ConcurrentMap<String, Counter> requestCountersCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> errorCountersCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> tokenCountersCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> responseTimersCache = new ConcurrentHashMap<>();

    /**
     * 记录请求次数
     */
    public void recordRequest(String userId, String appId, String taskId, String modelName, String status) {
        String normalizedUserId = normalizeTag(userId);
        String normalizedAppId = normalizeTag(appId);
        String normalizedTaskId = normalizeTag(taskId);
        String normalizedModelName = normalizeTag(modelName);
        String normalizedStatus = normalizeTag(status);
        String key = String.format("%s_%s_%s_%s_%s", normalizedUserId, normalizedAppId, normalizedTaskId, normalizedModelName, normalizedStatus);
        Counter counter = requestCountersCache.computeIfAbsent(key, k ->
                Counter.builder("ai_model_requests_total")
                        .description("AI模型总请求次数")
                        .tag("user_id", normalizedUserId)
                        .tag("app_id", normalizedAppId)
                        .tag("task_id", normalizedTaskId)
                        .tag("model_name", normalizedModelName)
                        .tag("status", normalizedStatus)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    /**
     * 记录错误
     */
    public void recordError(String userId, String appId, String taskId, String modelName, String errorMessage) {
        String normalizedUserId = normalizeTag(userId);
        String normalizedAppId = normalizeTag(appId);
        String normalizedTaskId = normalizeTag(taskId);
        String normalizedModelName = normalizeTag(modelName);
        String normalizedErrorMessage = normalizeTag(errorMessage);
        String key = String.format("%s_%s_%s_%s_%s", normalizedUserId, normalizedAppId, normalizedTaskId, normalizedModelName, normalizedErrorMessage);
        Counter counter = errorCountersCache.computeIfAbsent(key, k ->
                Counter.builder("ai_model_errors_total")
                        .description("AI模型错误次数")
                        .tag("user_id", normalizedUserId)
                        .tag("app_id", normalizedAppId)
                        .tag("task_id", normalizedTaskId)
                        .tag("model_name", normalizedModelName)
                        .tag("error_message", normalizedErrorMessage)
                        .register(meterRegistry)
        );
        counter.increment();
    }

    /**
     * 记录Token消耗
     */
    public void recordTokenUsage(String userId, String appId, String taskId, String modelName,
                                 String tokenType, long tokenCount) {
        String normalizedUserId = normalizeTag(userId);
        String normalizedAppId = normalizeTag(appId);
        String normalizedTaskId = normalizeTag(taskId);
        String normalizedModelName = normalizeTag(modelName);
        String normalizedTokenType = normalizeTag(tokenType);
        String key = String.format("%s_%s_%s_%s_%s", normalizedUserId, normalizedAppId, normalizedTaskId, normalizedModelName, normalizedTokenType);
        Counter counter = tokenCountersCache.computeIfAbsent(key, k ->
                Counter.builder("ai_model_tokens_total")
                        .description("AI模型Token消耗总数")
                        .tag("user_id", normalizedUserId)
                        .tag("app_id", normalizedAppId)
                        .tag("task_id", normalizedTaskId)
                        .tag("model_name", normalizedModelName)
                        .tag("token_type", normalizedTokenType)
                        .register(meterRegistry)
        );
        counter.increment(tokenCount);
    }

    /**
     * 记录响应时间
     */
    public void recordResponseTime(String userId, String appId, String taskId, String modelName, Duration duration) {
        String normalizedUserId = normalizeTag(userId);
        String normalizedAppId = normalizeTag(appId);
        String normalizedTaskId = normalizeTag(taskId);
        String normalizedModelName = normalizeTag(modelName);
        String key = String.format("%s_%s_%s_%s", normalizedUserId, normalizedAppId, normalizedTaskId, normalizedModelName);
        Timer timer = responseTimersCache.computeIfAbsent(key, k ->
                Timer.builder("ai_model_response_duration_seconds")
                        .description("AI模型响应时间")
                        .tag("user_id", normalizedUserId)
                        .tag("app_id", normalizedAppId)
                        .tag("task_id", normalizedTaskId)
                        .tag("model_name", normalizedModelName)
                        .register(meterRegistry)
        );
        timer.record(duration);
    }

    private String normalizeTag(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.trim();
    }
}
