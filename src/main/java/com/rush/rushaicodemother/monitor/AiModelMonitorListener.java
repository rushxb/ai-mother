package com.rush.rushaicodemother.monitor;

import com.rush.rushaicodemother.ai.provenance.AiModelProvenanceFactory;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.enums.GenerationModelCallStatus;
import com.rush.rushaicodemother.model.enums.GenerationModelUsageSource;
import com.rush.rushaicodemother.model.enums.ModelInvocationBillingMode;
import com.rush.rushaicodemother.model.enums.ModelInvocationPurpose;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.service.aimodel.AiModelCircuitBreaker;
import com.rush.rushaicodemother.service.trace.GenerationModelCallCommand;
import com.rush.rushaicodemother.service.trace.GenerationModelCallProvenance;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 每个模型调用的统一指标、断路器和来源侦听器。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiModelMonitorListener implements ChatModelListener {

    private static final String REQUEST_START_TIME_KEY = "request_start_time";
    private static final String MODEL_CALL_ID_KEY = "model_call_id";
    private static final String MONITOR_CONTEXT_KEY = "monitor_context";
    private static final String MODEL_PROVIDER_KEY = "configured_model_provider";
    private static final String CONFIGURED_MODEL_KEY = "configured_model_id";
    private static final String CONFIGURED_MAX_OUTPUT_TOKENS_KEY = "configured_max_output_tokens";
    private static final String PROVENANCE_KEY = "model_request_provenance";
    private static final String PREFLIGHT_TOKEN_ESTIMATE_KEY = "preflight_token_estimate";
    private static final String PROVIDER_ATTEMPT_SPAN_KEY = "provider_attempt_span";

    private final AiModelMetricsCollector aiModelMetricsCollector;
    private final GenerationTraceService generationTraceService;
    private final AiModelCircuitBreaker aiModelCircuitBreaker;
    private final AiModelProvenanceFactory provenanceFactory;
    private final ModelTokenUsageEstimator tokenUsageEstimator;
    private final GenerationPerformanceMonitorService performanceMonitorService;
    private final List<AiModelInvocationObserver> invocationObservers;

    /** 绑定隐藏在 OpenAI 兼容传输背后的真实提供商身份。 */
    public ChatModelListener forModel(String provider, String model) {
        return new BoundChatModelListener(normalize(provider), normalize(model), null);
    }

    /** 绑定真实 provider 身份与已审核的最大输出上限。 */
    public ChatModelListener forModel(String provider, String model, int maxOutputTokens) {
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("configured model max output tokens must be positive");
        }
        return new BoundChatModelListener(
                normalize(provider), normalize(model), maxOutputTokens);
    }

    /**
 * 响应请求事件。
 *
 * @param requestContext 请求上下文
 */
    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        Map<Object, Object> attributes = requestContext.attributes();
        attributes.put(REQUEST_START_TIME_KEY, Instant.now());
        attributes.put(MODEL_CALL_ID_KEY, UUID.randomUUID().toString());

        MonitorContext monitorContext = MonitorContextHolder.getContext();
        if (monitorContext == null) {
            monitorContext = defaultMonitorContext();
        }
        attributes.put(MONITOR_CONTEXT_KEY, monitorContext);

        ModelIdentity identity = identity(attributes, requestContext.chatRequest().modelName());
        attributes.put(PROVENANCE_KEY, provenanceFactory.create(
                requestContext.chatRequest(), identity.provider(), identity.model()));
        recordStartedCall(attributes, monitorContext, identity, requestContext.chatRequest());
        notifyInvocationObservers(identity);
        attributes.put(PROVIDER_ATTEMPT_SPAN_KEY, performanceMonitorService.startSpan(
                monitorContext.getTaskId(), "model_provider_attempt", GenerationSpanCategory.MODEL));
        aiModelMetricsCollector.recordRequest(
                identity.provider(), monitorContext.getUserId(), monitorContext.getAppId(),
                monitorContext.getTaskId(), identity.model(), "started");
    }

    /**
 * 响应响应事件。
 *
 * @param responseContext 响应上下文
 */
    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        Map<Object, Object> attributes = responseContext.attributes();
        MonitorContext context = monitorContext(attributes);
        String responseModel = responseContext.chatResponse() == null
                ? null : responseContext.chatResponse().modelName();
        ModelIdentity identity = identity(attributes, responseModel);
        closeProviderAttemptSpan(attributes, "success", identity, null);

        aiModelMetricsCollector.recordRequest(
                identity.provider(), context.getUserId(), context.getAppId(), context.getTaskId(),
                identity.model(), "success");
        aiModelCircuitBreaker.recordSuccess(identity.provider(), identity.model());
        Duration responseTime = recordResponseTime(
                attributes, identity, context.getUserId(), context.getAppId(), context.getTaskId());

        TokenUsage tokenUsage = responseContext.chatResponse() == null
                || responseContext.chatResponse().metadata() == null
                ? null
                : responseContext.chatResponse().metadata().tokenUsage();
        recordSuccessfulCall(responseContext, context, identity, tokenUsage, responseTime);
    }

    /**
 * 响应错误事件。
 *
 * @param errorContext 错误上下文
 */
    @Override
    public void onError(ChatModelErrorContext errorContext) {
        Map<Object, Object> attributes = errorContext.attributes();
        MonitorContext context = monitorContext(attributes);
        ModelIdentity identity = identity(attributes, errorContext.chatRequest().modelName());
        Throwable failure = errorContext.error();
        String errorMessage = failure == null ? null : failure.getMessage();
        String errorCategory = GenerationErrorClassifier.classify(failure).category();
        closeProviderAttemptSpan(attributes, "failed", identity, errorCategory);

        aiModelMetricsCollector.recordRequest(
                identity.provider(), context.getUserId(), context.getAppId(), context.getTaskId(),
                identity.model(), "error");
        aiModelMetricsCollector.recordError(
                identity.provider(), context.getUserId(), context.getAppId(), context.getTaskId(),
                identity.model(), errorMessage);
        aiModelCircuitBreaker.recordFailure(identity.provider(), identity.model(), failure);
        Duration responseTime = recordResponseTime(
                attributes, identity, context.getUserId(), context.getAppId(), context.getTaskId());
        TokenSnapshot tokens = preflightTokenEstimate(attributes);
        aiModelMetricsCollector.recordUsageResolution(
                identity.provider(), identity.model(), tokens.usageSource().name().toLowerCase());
        recordTokenSnapshotMetrics(tokens, identity, context);
        recordCall(
                attributes,
                context,
                identity,
                GenerationModelCallStatus.ERROR,
                null,
                tokens.promptTokens(),
                tokens.completionTokens(),
                tokens.totalTokens(),
                responseTime,
                null,
                tokens.usageSource(),
                errorCategory,
                false
        );
    }

    /** 记录{@code Successful}调用相关指标或状态。 */
    private void recordSuccessfulCall(ChatModelResponseContext responseContext,
                                      MonitorContext context,
                                      ModelIdentity identity,
                                      TokenUsage tokenUsage,
                                      Duration responseTime) {
        TokenSnapshot tokens = resolveTokenUsage(responseContext, tokenUsage);
        aiModelMetricsCollector.recordUsageResolution(
                identity.provider(), identity.model(), tokens.usageSource().name().toLowerCase());
        recordTokenSnapshotMetrics(tokens, identity, context);
        String finishReason = responseContext.chatResponse() == null
                || responseContext.chatResponse().metadata() == null
                || responseContext.chatResponse().metadata().finishReason() == null
                ? null
                : responseContext.chatResponse().metadata().finishReason().name();
        String providerRequestId = responseContext.chatResponse() == null
                ? null : responseContext.chatResponse().id();
        recordCall(
                responseContext.attributes(),
                context,
                identity,
                GenerationModelCallStatus.SUCCESS,
                providerRequestId,
                tokens.promptTokens(),
                tokens.completionTokens(),
                tokens.totalTokens(),
                responseTime,
                finishReason,
                tokens.usageSource(),
                null,
                false
        );
    }

    /** 物理 provider 调用前先建立持久 STARTED 账本；失败时必须阻止未记账请求继续。 */
    private void recordStartedCall(Map<Object, Object> attributes,
                                   MonitorContext context,
                                   ModelIdentity identity,
                                   dev.langchain4j.model.chat.request.ChatRequest request) {
        EstimatedModelTokenUsage estimate = tokenUsageEstimator.estimate(request, null);
        estimate = applyConfiguredOutputUpperBound(attributes, estimate);
        if (estimate == null || estimate.totalTokens() <= 0) {
            throw new IllegalStateException("model invocation ledger usage estimate is unavailable");
        }
        attributes.put(PREFLIGHT_TOKEN_ESTIMATE_KEY, estimate);
        recordCall(
                attributes,
                context,
                identity,
                GenerationModelCallStatus.STARTED,
                null,
                estimate.promptTokens(),
                estimate.completionTokens(),
                estimate.totalTokens(),
                Duration.ZERO,
                null,
                GenerationModelUsageSource.ESTIMATED,
                null,
                true
        );
    }

    private EstimatedModelTokenUsage applyConfiguredOutputUpperBound(
            Map<Object, Object> attributes,
            EstimatedModelTokenUsage estimate) {
        if (estimate == null) {
            return null;
        }
        Object configuredValue = attributes.get(CONFIGURED_MAX_OUTPUT_TOKENS_KEY);
        if (!(configuredValue instanceof Integer configuredMaxOutputTokens)
                || configuredMaxOutputTokens <= estimate.completionTokens()) {
            return estimate;
        }
        int promptTokens = estimate.promptTokens();
        int completionTokens = Math.min(
                configuredMaxOutputTokens,
                Integer.MAX_VALUE - promptTokens);
        return new EstimatedModelTokenUsage(
                promptTokens,
                completionTokens,
                promptTokens + completionTokens);
    }

    /**
     * 返回调用前已经持久化的保守估算。
     *
     * <p>失败回调不再重新计算，避免请求参数或 tokenizer 版本在调用期间变化；
     * 缺失时保持 UNAVAILABLE，后续结算会 fail closed，不会解释为零。</p>
     */
    private TokenSnapshot preflightTokenEstimate(Map<Object, Object> attributes) {
        Object value = attributes.get(PREFLIGHT_TOKEN_ESTIMATE_KEY);
        if (!(value instanceof EstimatedModelTokenUsage estimate) || estimate.totalTokens() <= 0) {
            return TokenSnapshot.unavailable();
        }
        return new TokenSnapshot(
                estimate.promptTokens(),
                estimate.completionTokens(),
                estimate.totalTokens(),
                GenerationModelUsageSource.ESTIMATED);
    }

    /** 记录调用相关指标或状态。 */
    private void recordCall(Map<Object, Object> attributes,
                            MonitorContext context,
                            ModelIdentity identity,
                            GenerationModelCallStatus status,
                            String providerRequestId,
                            Integer promptTokens,
                            Integer completionTokens,
                            Integer totalTokens,
                            Duration responseTime,
                            String finishReason,
                            GenerationModelUsageSource usageSource,
                            String errorCategory,
                            boolean failClosed) {
        Long appId = parsePositiveLong(context.getAppId());
        Long userId = parsePositiveLong(context.getUserId());
        ModelInvocationPurpose invocationPurpose = context.getInvocationPurpose() == null
                ? ModelInvocationPurpose.GENERATION : context.getInvocationPurpose();
        ModelInvocationBillingMode billingMode = context.getBillingMode() == null
                ? ModelInvocationBillingMode.BILLABLE : context.getBillingMode();
        String exemptionReason = normalizeExemptionReason(context.getBillingExemptionReason());
        Object callId = attributes.get(MODEL_CALL_ID_KEY);
        Object provenance = attributes.get(PROVENANCE_KEY);
        boolean invalidContext = context.getTaskId() == null
                || context.getTaskId().isBlank()
                || "none".equals(context.getTaskId())
                || userId == null
                || (invocationPurpose == ModelInvocationPurpose.GENERATION && appId == null)
                || (billingMode == ModelInvocationBillingMode.EXEMPT && exemptionReason == null)
                || (billingMode == ModelInvocationBillingMode.BILLABLE && exemptionReason != null)
                || !(callId instanceof String callIdText)
                || !(provenance instanceof GenerationModelCallProvenance);
        if (invalidContext) {
            if (failClosed) {
                throw new IllegalStateException(
                        "physical model invocation requires a complete billing context");
            }
            return;
        }
        String callIdText = (String) callId;
        GenerationModelCallProvenance callProvenance =
                (GenerationModelCallProvenance) provenance;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            generationTraceService.recordModelCall(new GenerationModelCallCommand(
                    callIdText,
                    context.getTaskId(),
                    appId,
                    userId,
                    invocationPurpose,
                    billingMode,
                    exemptionReason,
                    identity.provider(),
                    identity.model(),
                    status,
                    providerRequestId,
                    promptTokens,
                    completionTokens,
                    totalTokens,
                    responseTime == null ? 0L : Math.max(0L, responseTime.toMillis()),
                    finishReason,
                    usageSource,
                    errorCategory,
                    callProvenance
            ));
        } catch (RuntimeException persistenceFailure) {
            aiModelMetricsCollector.recordTracePersistenceFailure(
                    identity.provider(), identity.model(), status.name().toLowerCase());
            if (failClosed) {
                throw persistenceFailure;
            }
            log.error("AI model invocation provenance persistence failed, provider={}, model={}, status={}",
                    identity.provider(), identity.model(), status,
                    LogExceptionSanitizer.sanitize(persistenceFailure));
        }
    }

    private Duration recordResponseTime(Map<Object, Object> attributes,
                                        ModelIdentity identity,
                                        String userId,
                                        String appId,
                                        String taskId) {
        Instant startTime = (Instant) attributes.get(REQUEST_START_TIME_KEY);
        if (startTime == null) {
            return null;
        }
        Duration responseTime = Duration.between(startTime, Instant.now());
        aiModelMetricsCollector.recordResponseTime(
                identity.provider(), userId, appId, taskId, identity.model(), responseTime);
        return responseTime;
    }

    private void closeProviderAttemptSpan(Map<Object, Object> attributes,
                                          String status,
                                          ModelIdentity identity,
                                          String errorCategory) {
        Object timer = attributes.get(PROVIDER_ATTEMPT_SPAN_KEY);
        if (!(timer instanceof GenerationPerformanceMonitorService.SpanTimer spanTimer)) {
            return;
        }
        String detail = "provider=" + identity.provider()
                + ",model=" + identity.model()
                + (errorCategory == null ? "" : ",errorCategory=" + errorCategory);
        spanTimer.close(status, detail);
    }

    private void recordTokenSnapshotMetrics(TokenSnapshot tokenUsage,
                                            ModelIdentity identity,
                                            MonitorContext context) {
        if (tokenUsage == null || tokenUsage.usageSource() == GenerationModelUsageSource.UNAVAILABLE) {
            return;
        }
        recordTokenMetric(identity, context, "input", tokenUsage.promptTokens());
        recordTokenMetric(identity, context, "output", tokenUsage.completionTokens());
        recordTokenMetric(identity, context, "total", tokenUsage.totalTokens());
    }

    private void recordTokenMetric(ModelIdentity identity,
                                   MonitorContext context,
                                   String type,
                                   Integer count) {
        if (count == null) {
            return;
        }
        aiModelMetricsCollector.recordTokenUsage(
                identity.provider(), context.getUserId(), context.getAppId(), context.getTaskId(),
                identity.model(), type, count);
    }

    /** 规范化令牌用量。 */
    private TokenSnapshot normalizeTokenUsage(TokenUsage tokenUsage) {
        if (tokenUsage == null
                || tokenUsage.inputTokenCount() == null
                || tokenUsage.outputTokenCount() == null
                || tokenUsage.inputTokenCount() < 0
                || tokenUsage.outputTokenCount() < 0) {
            return TokenSnapshot.unavailable();
        }
        int promptTokens = tokenUsage.inputTokenCount();
        int completionTokens = tokenUsage.outputTokenCount();
        long calculatedTotal = (long) promptTokens + completionTokens;
        if (calculatedTotal > Integer.MAX_VALUE) {
            return TokenSnapshot.unavailable();
        }
        Integer reportedTotal = tokenUsage.totalTokenCount();
        if (reportedTotal != null && reportedTotal == calculatedTotal) {
            return new TokenSnapshot(
                    promptTokens, completionTokens, reportedTotal, GenerationModelUsageSource.OFFICIAL);
        }
        return new TokenSnapshot(
                promptTokens, completionTokens, (int) calculatedTotal, GenerationModelUsageSource.ESTIMATED);
    }

    /** Provider 未返回可用 usage 时，使用同一 tokenizer seam 产生保守、可审计的估算。 */
    private TokenSnapshot resolveTokenUsage(ChatModelResponseContext responseContext,
                                            TokenUsage providerUsage) {
        TokenSnapshot providerSnapshot = normalizeTokenUsage(providerUsage);
        if (providerSnapshot.usageSource() != GenerationModelUsageSource.UNAVAILABLE) {
            return providerSnapshot;
        }
        try {
            EstimatedModelTokenUsage estimate = tokenUsageEstimator.estimate(
                    responseContext.chatRequest(), responseContext.chatResponse());
            if (estimate == null || estimate.totalTokens() <= 0) {
                return TokenSnapshot.unavailable();
            }
            return new TokenSnapshot(
                    estimate.promptTokens(),
                    estimate.completionTokens(),
                    estimate.totalTokens(),
                    GenerationModelUsageSource.ESTIMATED
            );
        } catch (RuntimeException estimationFailure) {
            log.warn("AI model token usage estimation failed: {}",
                    LogExceptionSanitizer.sanitize(estimationFailure));
            return TokenSnapshot.unavailable();
        }
    }

    private MonitorContext monitorContext(Map<Object, Object> attributes) {
        MonitorContext context = (MonitorContext) attributes.get(MONITOR_CONTEXT_KEY);
        if (context == null) {
            context = MonitorContextHolder.getContext();
        }
        return context == null ? defaultMonitorContext() : context;
    }

    private ModelIdentity identity(Map<Object, Object> attributes, String fallbackModel) {
        String provider = attributes.get(MODEL_PROVIDER_KEY) instanceof String value
                ? value : "unknown";
        String model = attributes.get(CONFIGURED_MODEL_KEY) instanceof String value
                ? value : fallbackModel;
        return new ModelIdentity(normalize(provider), normalize(model));
    }

    private MonitorContext defaultMonitorContext() {
        return MonitorContext.builder()
                .userId("anonymous")
                .appId("none")
                .taskId("none")
                .invocationPurpose(ModelInvocationPurpose.GENERATION)
                .billingMode(ModelInvocationBillingMode.BILLABLE)
                .build();
    }

    private String normalizeExemptionReason(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "_");
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    /** 处理通知调用观察者。 */
    private void notifyInvocationObservers(ModelIdentity identity) {
        for (AiModelInvocationObserver observer : invocationObservers) {
            try {
                observer.onRequest(identity.provider(), identity.model());
            } catch (RuntimeException failure) {
                log.warn("AI 模型物理请求观察器执行失败，provider={}, model={}",
                        identity.provider(), identity.model(),
                        LogExceptionSanitizer.sanitize(failure));
            }
        }
    }

    /** 解析正数{@code Long}。 */
    private Long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private record ModelIdentity(String provider, String model) {
    }

    private record TokenSnapshot(Integer promptTokens,
                                 Integer completionTokens,
                                 Integer totalTokens,
                                 GenerationModelUsageSource usageSource) {

        private static TokenSnapshot unavailable() {
            return new TokenSnapshot(null, null, null, GenerationModelUsageSource.UNAVAILABLE);
        }
    }

    private final class BoundChatModelListener implements ChatModelListener {

        private final String provider;
        private final String model;
        private final Integer maxOutputTokens;

        private BoundChatModelListener(String provider,
                                       String model,
                                       Integer maxOutputTokens) {
            this.provider = provider;
            this.model = model;
            this.maxOutputTokens = maxOutputTokens;
        }

        /**
 * 响应请求事件。
 *
 * @param requestContext 请求上下文
 */
        @Override
        public void onRequest(ChatModelRequestContext requestContext) {
            bind(requestContext.attributes());
            AiModelMonitorListener.this.onRequest(requestContext);
        }

        /**
 * 响应响应事件。
 *
 * @param responseContext 响应上下文
 */
        @Override
        public void onResponse(ChatModelResponseContext responseContext) {
            bind(responseContext.attributes());
            AiModelMonitorListener.this.onResponse(responseContext);
        }

        /**
 * 响应错误事件。
 *
 * @param errorContext 错误上下文
 */
        @Override
        public void onError(ChatModelErrorContext errorContext) {
            bind(errorContext.attributes());
            AiModelMonitorListener.this.onError(errorContext);
        }

        private void bind(Map<Object, Object> attributes) {
            attributes.put(MODEL_PROVIDER_KEY, provider);
            attributes.put(CONFIGURED_MODEL_KEY, model);
            if (maxOutputTokens != null) {
                attributes.put(CONFIGURED_MAX_OUTPUT_TOKENS_KEY, maxOutputTokens);
            }
        }
    }
}
