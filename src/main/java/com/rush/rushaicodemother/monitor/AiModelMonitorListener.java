package com.rush.rushaicodemother.monitor;

import com.rush.rushaicodemother.ai.provenance.AiModelProvenanceFactory;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.enums.GenerationModelCallStatus;
import com.rush.rushaicodemother.model.enums.GenerationModelUsageSource;
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
import java.util.Map;
import java.util.UUID;

/** Unified metrics, circuit-breaker and provenance listener for every model invocation. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiModelMonitorListener implements ChatModelListener {

    private static final String REQUEST_START_TIME_KEY = "request_start_time";
    private static final String MODEL_CALL_ID_KEY = "model_call_id";
    private static final String MONITOR_CONTEXT_KEY = "monitor_context";
    private static final String MODEL_PROVIDER_KEY = "configured_model_provider";
    private static final String CONFIGURED_MODEL_KEY = "configured_model_id";
    private static final String PROVENANCE_KEY = "model_request_provenance";

    private final AiModelMetricsCollector aiModelMetricsCollector;
    private final GenerationTraceService generationTraceService;
    private final AiModelCircuitBreaker aiModelCircuitBreaker;
    private final AiModelProvenanceFactory provenanceFactory;

    /** Binds the real provider identity hidden behind an OpenAI-compatible transport. */
    public ChatModelListener forModel(String provider, String model) {
        return new BoundChatModelListener(normalize(provider), normalize(model));
    }

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
        try {
            attributes.put(PROVENANCE_KEY, provenanceFactory.create(
                    requestContext.chatRequest(), identity.provider(), identity.model()));
        } catch (RuntimeException provenanceFailure) {
            log.warn("AI model request provenance could not be created, provider={}, model={}",
                    identity.provider(), identity.model(), LogExceptionSanitizer.sanitize(provenanceFailure));
        }
        aiModelMetricsCollector.recordRequest(
                identity.provider(), monitorContext.getUserId(), monitorContext.getAppId(),
                monitorContext.getTaskId(), identity.model(), "started");
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        Map<Object, Object> attributes = responseContext.attributes();
        MonitorContext context = monitorContext(attributes);
        String responseModel = responseContext.chatResponse() == null
                ? null : responseContext.chatResponse().modelName();
        ModelIdentity identity = identity(attributes, responseModel);

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
        recordTokenMetrics(tokenUsage, identity, context);
        recordSuccessfulCall(responseContext, context, identity, tokenUsage, responseTime);
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        Map<Object, Object> attributes = errorContext.attributes();
        MonitorContext context = monitorContext(attributes);
        ModelIdentity identity = identity(attributes, errorContext.chatRequest().modelName());
        Throwable failure = errorContext.error();
        String errorMessage = failure == null ? null : failure.getMessage();
        String errorCategory = GenerationErrorClassifier.classify(failure).category();

        aiModelMetricsCollector.recordRequest(
                identity.provider(), context.getUserId(), context.getAppId(), context.getTaskId(),
                identity.model(), "error");
        aiModelMetricsCollector.recordError(
                identity.provider(), context.getUserId(), context.getAppId(), context.getTaskId(),
                identity.model(), errorMessage);
        aiModelCircuitBreaker.recordFailure(identity.provider(), identity.model(), failure);
        Duration responseTime = recordResponseTime(
                attributes, identity, context.getUserId(), context.getAppId(), context.getTaskId());
        recordCall(
                attributes,
                context,
                identity,
                GenerationModelCallStatus.ERROR,
                null,
                null,
                null,
                null,
                responseTime,
                null,
                GenerationModelUsageSource.UNAVAILABLE,
                errorCategory
        );
    }

    private void recordSuccessfulCall(ChatModelResponseContext responseContext,
                                      MonitorContext context,
                                      ModelIdentity identity,
                                      TokenUsage tokenUsage,
                                      Duration responseTime) {
        TokenSnapshot tokens = normalizeTokenUsage(tokenUsage);
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
                null
        );
    }

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
                            String errorCategory) {
        Long appId = parsePositiveLong(context.getAppId());
        Long userId = parsePositiveLong(context.getUserId());
        Object callId = attributes.get(MODEL_CALL_ID_KEY);
        Object provenance = attributes.get(PROVENANCE_KEY);
        if ("none".equals(context.getTaskId())
                || appId == null
                || userId == null
                || !(callId instanceof String callIdText)
                || !(provenance instanceof GenerationModelCallProvenance callProvenance)) {
            return;
        }
        try {
            generationTraceService.recordModelCall(new GenerationModelCallCommand(
                    callIdText,
                    context.getTaskId(),
                    appId,
                    userId,
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

    private void recordTokenMetrics(TokenUsage tokenUsage,
                                    ModelIdentity identity,
                                    MonitorContext context) {
        if (tokenUsage == null) {
            return;
        }
        recordTokenMetric(identity, context, "input", tokenUsage.inputTokenCount());
        recordTokenMetric(identity, context, "output", tokenUsage.outputTokenCount());
        recordTokenMetric(identity, context, "total", tokenUsage.totalTokenCount());
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
                .build();
    }

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

        private BoundChatModelListener(String provider, String model) {
            this.provider = provider;
            this.model = model;
        }

        @Override
        public void onRequest(ChatModelRequestContext requestContext) {
            bind(requestContext.attributes());
            AiModelMonitorListener.this.onRequest(requestContext);
        }

        @Override
        public void onResponse(ChatModelResponseContext responseContext) {
            bind(responseContext.attributes());
            AiModelMonitorListener.this.onResponse(responseContext);
        }

        @Override
        public void onError(ChatModelErrorContext errorContext) {
            bind(errorContext.attributes());
            AiModelMonitorListener.this.onError(errorContext);
        }

        private void bind(Map<Object, Object> attributes) {
            attributes.put(MODEL_PROVIDER_KEY, provider);
            attributes.put(CONFIGURED_MODEL_KEY, model);
        }
    }
}
