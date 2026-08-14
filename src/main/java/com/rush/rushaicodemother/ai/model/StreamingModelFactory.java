package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.ai.model.failover.AiModelCandidate;
import com.rush.rushaicodemother.ai.model.failover.FailoverChatModel;
import com.rush.rushaicodemother.ai.model.failover.FailoverStreamingChatModel;
import com.rush.rushaicodemother.ai.model.failover.FirstTokenHedgePolicy;
import com.rush.rushaicodemother.ai.model.failover.FirstTokenHedgeScheduler;
import com.rush.rushaicodemother.ai.model.capacity.AiModelCapacityGuard;
import com.rush.rushaicodemother.ai.model.capacity.CapacityControlledChatModel;
import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.monitor.AiModelMonitorListener;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeConfiguration;
import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeService;
import com.rush.rushaicodemother.service.aimodel.AiModelSecretService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 流式模型工厂。
 * <p>
 * 根据 {@link GenerationPerformanceProfile} 动态创建不同配置的流式模型，
 * 实现模型选择策略的解耦。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamingModelFactory {

    private static final String MODEL_TYPE_CHAT = "chat";
    private static final String MODEL_TYPE_REASONING = "reasoning";
    private static final String MODEL_TYPE_ROUTING = "routing";

    private final AiModelMonitorListener aiModelMonitorListener;

    private final AiModelRuntimeService aiModelRuntimeService;

    private final AiModelRuntimeProperties runtimeProperties;

    private final OpenAiThinkingPolicy openAiThinkingPolicy;

    private final AiModelMetricsCollector aiModelMetricsCollector;

    private final AiModelCapacityGuard aiModelCapacityGuard;

    private final AiModelSecretService aiModelSecretService;

    private final FirstTokenHedgeScheduler firstTokenHedgeScheduler;

    private final AiStreamingCallRuntime streamingCallRuntime;

    /**
     * 根据性能配置创建流式模型。
     * <p>
     * 模型配置统一从数据库读取。
     *
     * @param profile 性能配置
     * @return 流式模型实例
     */
    public StreamingChatModel createModel(GenerationPerformanceProfile profile) {
        String modelType = resolveModelType(profile.modelTier());
        List<AiModelRuntimeConfiguration> models = getRequiredEnabledModelsByType(
                modelType, "生成性能配置 " + profile.modelTier());
        AiModelRuntimeConfiguration dbModel = models.getFirst();
        log.info("使用数据库生成模型配置: provider={}, modelId={}, modelType={}, thinking={}",
                dbModel.provider(), dbModel.modelId(), dbModel.modelType(),
                profile.thinkingEnabled());
        return createStreamingPool(
                models,
                model -> createModelFromDb(model, profile.thinkingEnabled()),
                runtimeProperties.getGenerationTimeout()
        );
    }

    /**
     * 创建一个任务范围的流池，其具体提供者尝试共享一个超时。
     *
     * <p>回调在每个真实提供者请求之前立即调用，包括
     * 故障转移候选者。这让任务运行时考虑物理请求而不是
     * 将一个逻辑代理轮次计为一个请求。</p>
     */
    public StreamingChatModel createExecutionModel(GenerationPerformanceProfile profile,
                                                    Duration totalTimeout,
                                                    Runnable beforeProviderAttempt) {
        return createExecutionStreamingModel(
                profile, totalTimeout, beforeProviderAttempt, null, true);
    }

    /** 创建区分逻辑模型回合与 provider 故障转移预算的任务级流式模型。 */
    public StreamingChatModel createExecutionModel(GenerationPerformanceProfile profile,
                                                    Duration totalTimeout,
                                                    Runnable beforeModelTurn,
                                                    Runnable beforeProviderFailoverAttempt) {
        return createExecutionStreamingModel(
                profile, totalTimeout, beforeModelTurn, beforeProviderFailoverAttempt, false);
    }

    /** 为 HTML、多文件等快速任务创建任务级 chat 流式模型。 */
    public StreamingChatModel createExecutionChatModel(Duration totalTimeout,
                                                       Runnable beforeModelTurn,
                                                       Runnable beforeProviderFailoverAttempt) {
        return createExecutionStreamingModel(
                GenerationPerformanceProfile.speedFirst(), totalTimeout,
                beforeModelTurn, beforeProviderFailoverAttempt, false);
    }

    /** 为受管同步编辑调用创建任务级故障转移模型。 */
    public ChatModel createExecutionRoutingChatModel(Duration totalTimeout,
                                                     Runnable beforeModelTurn,
                                                     Runnable beforeProviderFailoverAttempt) {
        if (totalTimeout == null || totalTimeout.isZero() || totalTimeout.isNegative()) {
            throw new IllegalArgumentException("execution model timeout must be positive");
        }
        Duration effectiveTimeout = totalTimeout.compareTo(runtimeProperties.getRoutingTimeout()) <= 0
                ? totalTimeout
                : runtimeProperties.getRoutingTimeout();
        List<AiModelRuntimeConfiguration> models = getRoutingModels(
                "任务级同步编辑执行");
        return createTimeBoundedChatPool(
                models, effectiveTimeout, 0, false,
                beforeModelTurn, beforeProviderFailoverAttempt);
    }

    /** 为受管 CREATE 规格调用创建共享任务截止时间与预算回调的同步模型。 */
    public ChatModel createExecutionCreateSpecChatModel(Duration totalTimeout,
                                                        Runnable beforeModelTurn,
                                                        Runnable beforeProviderFailoverAttempt) {
        if (totalTimeout == null || totalTimeout.isZero() || totalTimeout.isNegative()) {
            throw new IllegalArgumentException("CREATE 规格模型超时必须大于 0");
        }
        Duration effectiveTimeout = totalTimeout.compareTo(runtimeProperties.getCreateSpecTimeout()) <= 0
                ? totalTimeout
                : runtimeProperties.getCreateSpecTimeout();
        List<AiModelRuntimeConfiguration> models = getRequiredEnabledModelsByType(
                MODEL_TYPE_CHAT, "受管 CREATE 规格生成任务");
        return createTimeBoundedChatPool(
                models, effectiveTimeout, 0, false,
                beforeModelTurn, beforeProviderFailoverAttempt);
    }

    /**
     * 创建普通对话流式模型。
     * <p>
     * 从数据库读取 chat 类型模型。
     */
    public StreamingChatModel createChatModel() {
        List<AiModelRuntimeConfiguration> models = getRequiredEnabledModelsByType(
                MODEL_TYPE_CHAT, "普通对话/快速流式任务");
        AiModelRuntimeConfiguration dbModel = models.getFirst();
        log.info("使用数据库快速流式模型配置: provider={}, modelId={}, modelType={}",
                dbModel.provider(), dbModel.modelId(), dbModel.modelType());
        return createStreamingPool(
                models,
                model -> createModelFromDb(model, false),
                runtimeProperties.getGenerationTimeout()
        );
    }

    /**
     * 创建推理流式模型。
     * <p>
     * 从数据库读取 reasoning 类型模型。
     */
    public StreamingChatModel createReasoningModel() {
        List<AiModelRuntimeConfiguration> models = getRequiredEnabledModelsByType(
                MODEL_TYPE_REASONING, "思考/重型生成任务");
        AiModelRuntimeConfiguration dbModel = models.getFirst();
        log.info("使用数据库思考流式模型配置: provider={}, modelId={}, modelType={}",
                dbModel.provider(), dbModel.modelId(), dbModel.modelType());
        return createStreamingPool(
                models,
                model -> createModelFromDb(model, true),
                runtimeProperties.getGenerationTimeout()
        );
    }

    /**
     * 创建路由/轻量同步模型。
     * <p>
     * 使用数据库 chat 类型模型。
     */
    public ChatModel createRoutingChatModel() {
        return createRoutingChatModel(runtimeProperties.getRoutingTimeout(),
                runtimeProperties.getRoutingMaxRetries());
    }

    /**
     * 以统一容量门禁和物理调用账本执行管理端连接探测。
     * 调用方必须先绑定 CONNECTION_TEST 的 MonitorContext。
     */
    public String testConnection(AiModelRuntimeConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("AI model connection configuration is required");
        }
        int probeMaxTokens = Math.min(configuration.maxTokens(), 256);
        AiModelRuntimeConfiguration probe = new AiModelRuntimeConfiguration(
                configuration.provider(), configuration.modelId(), configuration.modelType(),
                configuration.baseUrl(), configuration.secretRef(),
                configuration.secretFingerprint(), configuration.secretKeyId(),
                probeMaxTokens, configuration.temperature(), configuration.supportsThinking());
        Duration timeout = runtimeProperties.getRoutingTimeout();
        ChatModel delegate = createChatModelFromDb(
                probe, timeout, 0, false, false, false);
        ChatModel audited = new CapacityControlledChatModel(
                probe.provider(), probe.modelId(), probe.maxTokens(), delegate,
                aiModelCapacityGuard, timeout,
                aiModelMonitorListener.forModel(
                        probe.provider(), probe.modelId(), probe.maxTokens()));
        return audited.chat("Hello, this is a connection test. Reply with 'OK' only.");
    }

    /**
     * 使用调用者提供的总挂钟超时创建路由模型。
     *
     * <p>托管编辑调用传递{@code maxRetries=0}；重试策略由任务运行时拥有
     * 而不是隐藏在提供者客户端内部。超时在候选者之间分配
     * 因此串行故障转移不能增加调用者的截止日期。</p>
     */
    public ChatModel createRoutingChatModel(Duration timeout, int maxRetries) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("routing model timeout must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("routing model maxRetries cannot be negative");
        }
        Duration effectiveTimeout = timeout.compareTo(runtimeProperties.getRoutingTimeout()) <= 0
                ? timeout
                : runtimeProperties.getRoutingTimeout();
        List<AiModelRuntimeConfiguration> models = getRoutingModels(
                "路由、意图识别与轻量同步任务");
        AiModelRuntimeConfiguration dbModel = models.getFirst();
        log.info("使用数据库快速同步模型配置: usage=routing, provider={}, modelId={}, modelType={}, timeoutSeconds={}, maxRetries={}",
                dbModel.provider(), dbModel.modelId(), dbModel.modelType(),
                effectiveTimeout.toSeconds(), maxRetries);
        return createTimeBoundedChatPool(models, effectiveTimeout, maxRetries, false);
    }

    /**
     * 创建 CREATE 创意规格同步模型。
     * <p>
     * 该调用只生成小型 JSON spec，不生成代码或大 patch，因此主链路可以保持短超时。
     */
    public ChatModel createCreateSpecChatModel() {
        List<AiModelRuntimeConfiguration> models = getRequiredEnabledModelsByType(
                MODEL_TYPE_CHAT, "CREATE 创意规格生成任务");
        AiModelRuntimeConfiguration dbModel = models.getFirst();
        log.info("使用数据库 CREATE Spec 快速模型配置: provider={}, modelId={}, modelType={}, timeoutSeconds={}, maxRetries={}",
                dbModel.provider(), dbModel.modelId(), dbModel.modelType(),
                runtimeProperties.getCreateSpecTimeout().toSeconds(), 0);
        return createTimeBoundedChatPool(
                models, runtimeProperties.getCreateSpecTimeout(), 0, false);
    }

    /**
     * 创建主同步模型。
     * <p>
     * 使用数据库 reasoning 类型模型。
     */
    public ChatModel createPrimaryChatModel() {
        List<AiModelRuntimeConfiguration> models = getRequiredEnabledModelsByType(
                MODEL_TYPE_REASONING, "主同步思考任务");
        AiModelRuntimeConfiguration dbModel = models.getFirst();
        log.info("使用数据库主思考模型配置: provider={}, modelId={}, modelType={}",
                dbModel.provider(), dbModel.modelId(), dbModel.modelType());
        return createChatPool(
                models,
                model -> createChatModelFromDb(
                        model, runtimeProperties.getGenerationTimeout(), null, true,
                        runtimeProperties.isGenerationLogRequests(),
                        runtimeProperties.isGenerationLogResponses()),
                runtimeProperties.getGenerationTimeout()
        );
    }

    /**
     * 根据模型层级解析模型类型
     */
    private String resolveModelType(GenerationPerformanceProfile.ModelTier modelTier) {
        return switch (modelTier) {
            case SPEED, BALANCED -> MODEL_TYPE_CHAT;
            case QUALITY -> MODEL_TYPE_REASONING;
        };
    }

    /** 获取并返回{@code Required}启用模型按类型。 */
    private List<AiModelRuntimeConfiguration> getRequiredEnabledModelsByType(String modelType, String usage) {
        try {
            List<AiModelRuntimeConfiguration> available =
                    aiModelRuntimeService.listRunnableModelsByType(modelType);
            if (available == null || available.isEmpty()) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "没有可运行的 " + modelType + " 模型"
                );
            }
            int limit = Math.max(1, runtimeProperties.getFailoverMaxCandidates());
            if (available.size() <= limit) {
                return available;
            }
            log.info("Limiting AI model request pool, modelType={}, usage={}, available={}, selected={}",
                    modelType, usage, available.size(), limit);
            return List.copyOf(available.subList(0, limit));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("读取 {} 模型配置失败，usage={}", modelType, usage, LogExceptionSanitizer.sanitize(e));
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取 AI 模型配置失败");
        }
    }

    /** 获取并返回路由模型。 */
    private List<AiModelRuntimeConfiguration> getRoutingModels(String usage) {
        try {
            return getRequiredEnabledModelsByType(MODEL_TYPE_ROUTING, usage);
        } catch (BusinessException unavailable) {
            log.info("未配置可运行的 routing 模型，回退到 chat 模型，usage={}", usage);
            return getRequiredEnabledModelsByType(MODEL_TYPE_CHAT, usage);
        }
    }

    /** 创建{@code Streaming}线程池。 */
    private StreamingChatModel createStreamingPool(
            List<AiModelRuntimeConfiguration> models,
            Function<AiModelRuntimeConfiguration, StreamingChatModel> modelFactory,
            Duration upstreamTimeout) {
        List<AiModelCandidate<StreamingChatModel>> candidates = models.stream()
                .map(model -> new AiModelCandidate<StreamingChatModel>(
                        model.provider(), model.modelId(),
                        streamingCallRuntime.capacityControlled(
                                model.provider(), model.modelId(), model.maxTokens(),
                                modelFactory.apply(model), aiModelCapacityGuard, upstreamTimeout,
                                aiModelMonitorListener.forModel(
                                        model.provider(), model.modelId(), model.maxTokens()))))
                .toList();
        if (candidates.size() == 1) {
            return candidates.getFirst().model();
        }
        return new FailoverStreamingChatModel(
                candidates, aiModelMetricsCollector, streamingCallRuntime.cancellationBridge());
    }

    private StreamingChatModel createTimeBoundedStreamingPool(
            List<AiModelRuntimeConfiguration> models,
            Duration totalTimeout,
            boolean enableThinking,
            Runnable beforeProviderAttempt) {
        List<AiModelCandidate<StreamingChatModel>> candidates = createTimeBoundedStreamingCandidates(
                models, totalTimeout, enableThinking);
        return new FailoverStreamingChatModel(
                candidates, aiModelMetricsCollector, beforeProviderAttempt,
                resolveFirstTokenHedgePolicy(candidates, totalTimeout),
                streamingCallRuntime.cancellationBridge());
    }

    private StreamingChatModel createTimeBoundedStreamingPool(
            List<AiModelRuntimeConfiguration> models,
            Duration totalTimeout,
            boolean enableThinking,
            Runnable beforeModelTurn,
            Runnable beforeProviderFailoverAttempt) {
        List<AiModelCandidate<StreamingChatModel>> candidates = createTimeBoundedStreamingCandidates(
                models, totalTimeout, enableThinking);
        return new FailoverStreamingChatModel(
                candidates, aiModelMetricsCollector,
                beforeModelTurn, beforeProviderFailoverAttempt,
                resolveFirstTokenHedgePolicy(candidates, totalTimeout),
                streamingCallRuntime.cancellationBridge());
    }

    /** 根据当前上下文解析{@code First}令牌{@code Hedge}策略。 */
    FirstTokenHedgePolicy resolveFirstTokenHedgePolicy(
            List<AiModelCandidate<StreamingChatModel>> candidates,
            Duration totalTimeout) {
        if (!runtimeProperties.isFirstTokenHedgeEnabled() || candidates.size() < 2) {
            return FirstTokenHedgePolicy.disabled();
        }
        Duration delay = runtimeProperties.getFirstTokenHedgeDelay();
        Duration firstCandidateTimeout = allocateTimeoutSlices(
                totalTimeout, candidates.size()).getFirst();
        if (delay == null || delay.isZero() || delay.isNegative()
                || delay.compareTo(firstCandidateTimeout) >= 0) {
            return FirstTokenHedgePolicy.disabled();
        }
        return new FirstTokenHedgePolicy(
                true,
                delay,
                runtimeProperties.isFirstTokenHedgeRequireDistinctProvider(),
                firstTokenHedgeScheduler
        );
    }

    /** 创建时间{@code Bounded}{@code Streaming}{@code Candidates}。 */
    private List<AiModelCandidate<StreamingChatModel>> createTimeBoundedStreamingCandidates(
            List<AiModelRuntimeConfiguration> models,
            Duration totalTimeout,
            boolean enableThinking) {
        List<Duration> timeoutSlices = allocateTimeoutSlices(totalTimeout, models.size());
        List<AiModelCandidate<StreamingChatModel>> candidates = new ArrayList<>(models.size());
        for (int index = 0; index < models.size(); index++) {
            AiModelRuntimeConfiguration model = models.get(index);
            StreamingChatModel streamingModel = createModelFromDb(
                    model, enableThinking, timeoutSlices.get(index));
            candidates.add(new AiModelCandidate<>(
                    model.provider(), model.modelId(),
                     streamingCallRuntime.capacityControlled(
                              model.provider(), model.modelId(), model.maxTokens(),
                              streamingModel, aiModelCapacityGuard, timeoutSlices.get(index),
                              aiModelMonitorListener.forModel(
                                      model.provider(), model.modelId(), model.maxTokens()))));
        }
        return List.copyOf(candidates);
    }

    /** 创建对话线程池。 */
    private ChatModel createChatPool(
            List<AiModelRuntimeConfiguration> models,
            Function<AiModelRuntimeConfiguration, ChatModel> modelFactory,
            Duration upstreamTimeout) {
        List<AiModelCandidate<ChatModel>> candidates = models.stream()
                .map(model -> new AiModelCandidate<ChatModel>(
                        model.provider(), model.modelId(),
                        new CapacityControlledChatModel(
                                model.provider(), model.modelId(), model.maxTokens(),
                                modelFactory.apply(model), aiModelCapacityGuard, upstreamTimeout,
                                aiModelMonitorListener.forModel(
                                        model.provider(), model.modelId(), model.maxTokens()))))
                .toList();
        if (candidates.size() == 1) {
            return candidates.getFirst().model();
        }
        return new FailoverChatModel(candidates, aiModelMetricsCollector);
    }

    private ChatModel createTimeBoundedChatPool(
            List<AiModelRuntimeConfiguration> models,
            Duration totalTimeout,
            int maxRetries,
            boolean enableThinking) {
        return createTimeBoundedChatPool(
                models, totalTimeout, maxRetries, enableThinking, null, null);
    }

    /** 创建时间{@code Bounded}对话线程池。 */
    private ChatModel createTimeBoundedChatPool(
            List<AiModelRuntimeConfiguration> models,
            Duration totalTimeout,
            int maxRetries,
            boolean enableThinking,
            Runnable beforeModelTurn,
            Runnable beforeProviderFailoverAttempt) {
        int attemptsPerCandidate = Math.addExact(maxRetries, 1);
        int timeoutSlices = Math.multiplyExact(models.size(), attemptsPerCandidate);
        List<Duration> attemptTimeouts = allocateTimeoutSlices(totalTimeout, timeoutSlices);
        List<AiModelCandidate<ChatModel>> candidates = new ArrayList<>(models.size());
        for (int index = 0; index < models.size(); index++) {
            AiModelRuntimeConfiguration model = models.get(index);
            Duration candidateTimeout = attemptTimeouts.get(index * attemptsPerCandidate);
            ChatModel chatModel = createChatModelFromDb(
                    model, candidateTimeout, maxRetries, enableThinking);
            candidates.add(new AiModelCandidate<>(
                    model.provider(), model.modelId(),
                    new CapacityControlledChatModel(
                            model.provider(), model.modelId(), model.maxTokens(),
                            chatModel, aiModelCapacityGuard, candidateTimeout,
                            aiModelMonitorListener.forModel(
                                    model.provider(), model.modelId(), model.maxTokens()))));
        }
        if (candidates.size() == 1 && beforeModelTurn == null
                && beforeProviderFailoverAttempt == null) {
            return candidates.getFirst().model();
        }
        return new FailoverChatModel(
                candidates, aiModelMetricsCollector, totalTimeout,
                beforeModelTurn, beforeProviderFailoverAttempt);
    }

    /** 创建执行{@code Streaming}模型。 */
    private StreamingChatModel createExecutionStreamingModel(
            GenerationPerformanceProfile profile,
            Duration totalTimeout,
            Runnable firstAdmission,
            Runnable failoverAdmission,
            boolean countEveryProviderAttempt) {
        if (totalTimeout == null || totalTimeout.isZero() || totalTimeout.isNegative()) {
            throw new IllegalArgumentException("execution model timeout must be positive");
        }
        Duration effectiveTimeout = totalTimeout.compareTo(runtimeProperties.getGenerationTimeout()) <= 0
                ? totalTimeout
                : runtimeProperties.getGenerationTimeout();
        String modelType = profile == null
                ? MODEL_TYPE_REASONING
                : resolveModelType(profile.modelTier());
        boolean enableThinking = profile == null || profile.thinkingEnabled();
        List<AiModelRuntimeConfiguration> models = getRequiredEnabledModelsByType(
                modelType, "task-scoped streaming execution");
        if (countEveryProviderAttempt) {
            return createTimeBoundedStreamingPool(
                    models, effectiveTimeout, enableThinking, firstAdmission);
        }
        return createTimeBoundedStreamingPool(
                models, effectiveTimeout, enableThinking,
                firstAdmission, failoverAdmission);
    }

    /** 返回{@code allocate}超时{@code Slices}。 */
    static List<Duration> allocateTimeoutSlices(Duration totalTimeout, int sliceCount) {
        if (totalTimeout == null || totalTimeout.isZero() || totalTimeout.isNegative()) {
            throw new IllegalArgumentException("total timeout must be positive");
        }
        if (sliceCount <= 0) {
            throw new IllegalArgumentException("timeout slice count must be positive");
        }
        long totalNanos = totalTimeout.toNanos();
        if (totalNanos < sliceCount) {
            throw new IllegalArgumentException("total timeout is too small for the failover pool");
        }
        long baseNanos = totalNanos / sliceCount;
        long remainder = totalNanos % sliceCount;
        List<Duration> slices = new ArrayList<>(sliceCount);
        for (int index = 0; index < sliceCount; index++) {
            long nanos = baseNanos + (index < remainder ? 1L : 0L);
            slices.add(Duration.ofNanos(nanos));
        }
        return List.copyOf(slices);
    }

    /**
     * 从数据库配置创建模型
     * <p>
     * 兼容性处理：
     * 1. 只有 supportsThinking=1 的模型才发送 thinking 参数
     * 2. temperature 范围根据模型提供商调整
     */
    private StreamingChatModel createModelFromDb(AiModelRuntimeConfiguration dbModel,
                                                  boolean enableThinking) {
        return createModelFromDb(
                dbModel, enableThinking, runtimeProperties.getGenerationTimeout());
    }

    /** 创建模型{@code From}{@code Db}。 */
    private StreamingChatModel createModelFromDb(AiModelRuntimeConfiguration dbModel,
                                                  boolean enableThinking,
                                                  Duration timeout) {
        var builder = OpenAiStreamingChatModel.builder()
                .httpClientBuilder(streamingCallRuntime.httpClientBuilder())
                .apiKey(aiModelSecretService.resolve(
                        dbModel.secretRef(), dbModel.secretFingerprint()))
                .baseUrl(dbModel.baseUrl())
                .modelName(dbModel.modelId())
                .temperature(resolveTemperature(dbModel))
                .logRequests(runtimeProperties.isGenerationLogRequests())
                .logResponses(runtimeProperties.isGenerationLogResponses())
                .timeout(timeout);

        applyMaxTokens(builder, dbModel);
        applyThinking(builder, dbModel, enableThinking);

        return builder.build();
    }

    private ChatModel createChatModelFromDb(AiModelRuntimeConfiguration dbModel,
                                            Duration timeout,
                                            Integer maxRetries) {
        return createChatModelFromDb(dbModel, timeout, maxRetries, false);
    }

    private ChatModel createChatModelFromDb(AiModelRuntimeConfiguration dbModel,
                                            Duration timeout,
                                            Integer maxRetries,
                                            boolean enableThinking) {
        return createChatModelFromDb(
                dbModel,
                timeout,
                maxRetries,
                enableThinking,
                runtimeProperties.isRoutingLogRequests(),
                runtimeProperties.isRoutingLogResponses()
        );
    }

    /** 创建对话模型{@code From}{@code Db}。 */
    private ChatModel createChatModelFromDb(AiModelRuntimeConfiguration dbModel,
                                            Duration timeout,
                                            Integer maxRetries,
                                            boolean enableThinking,
                                            boolean requestLogging,
                                            boolean responseLogging) {
        var builder = OpenAiChatModel.builder()
                .apiKey(aiModelSecretService.resolve(
                        dbModel.secretRef(), dbModel.secretFingerprint()))
                .baseUrl(dbModel.baseUrl())
                .modelName(dbModel.modelId())
                .temperature(resolveTemperature(dbModel))
                .logRequests(requestLogging)
                .logResponses(responseLogging);

        applyMaxTokens(builder, dbModel);
        applyThinking(builder, dbModel, enableThinking);
        if (timeout != null) {
            builder.timeout(timeout);
        }
        if (maxRetries != null) {
            builder.maxRetries(maxRetries);
        }

        return builder.build();
    }

    /**
     * 解析温度参数，确保在模型支持的范围内
     * <p>
     * 不同模型的 temperature 范围：
     * - DeepSeek: 0-2
     * - OpenAI: 0-2
     * - Claude: 0-1
     * - 通义千问: 0-2
     * - Xiaomi MiMo: 0-1.5
     * - 其他: 默认 0-1（安全范围）
     */
    private Double resolveTemperature(AiModelRuntimeConfiguration dbModel) {
        double temp = dbModel.temperature();
        String modelId = dbModel.modelId() != null ? dbModel.modelId().toLowerCase() : "";
        String provider = dbModel.provider() != null ? dbModel.provider().toLowerCase() : "";

        // Claude 模型限制 temperature 在 0-1 范围
        if (modelId.contains("claude") || provider.contains("anthropic")) {
            return Math.min(temp, 1.0);
        }

        if (modelId.startsWith("mimo-v2") || provider.equals("xiaomi")) {
            return Math.min(temp, 1.5);
        }

        // 其他模型使用原始值（OpenAI/DeepSeek/通义千问都支持 0-2）
        return temp;
    }

    private void applyThinking(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder,
                               AiModelRuntimeConfiguration model,
                               boolean enableThinking) {
        OpenAiThinkingPolicy.ThinkingConfiguration configuration =
                openAiThinkingPolicy.resolve(model, enableThinking);
        builder.returnThinking(configuration.returnThinking())
                .sendThinking(configuration.sendThinking());
        if (!configuration.customParameters().isEmpty()) {
            builder.customParameters(configuration.customParameters());
        }
    }

    private void applyMaxTokens(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder,
                                AiModelRuntimeConfiguration model) {
        if (isXiaomiMimoModel(model)) {
            builder.maxCompletionTokens(model.maxTokens());
            return;
        }
        builder.maxTokens(model.maxTokens());
    }

    private void applyMaxTokens(OpenAiChatModel.OpenAiChatModelBuilder builder,
                                AiModelRuntimeConfiguration model) {
        if (isXiaomiMimoModel(model)) {
            builder.maxCompletionTokens(model.maxTokens());
            return;
        }
        builder.maxTokens(model.maxTokens());
    }

    private boolean isXiaomiMimoModel(AiModelRuntimeConfiguration model) {
        if (model == null) {
            return false;
        }
        String provider = model.provider() != null ? model.provider().toLowerCase() : "";
        String modelId = model.modelId() != null ? model.modelId().toLowerCase() : "";
        return provider.equals("xiaomi") || modelId.startsWith("mimo-v2");
    }

    private void applyThinking(OpenAiChatModel.OpenAiChatModelBuilder builder,
                               AiModelRuntimeConfiguration model,
                               boolean enableThinking) {
        OpenAiThinkingPolicy.ThinkingConfiguration configuration =
                openAiThinkingPolicy.resolve(model, enableThinking);
        builder.returnThinking(configuration.returnThinking())
                .sendThinking(configuration.sendThinking());
        if (!configuration.customParameters().isEmpty()) {
            builder.customParameters(configuration.customParameters());
        }
    }

}
