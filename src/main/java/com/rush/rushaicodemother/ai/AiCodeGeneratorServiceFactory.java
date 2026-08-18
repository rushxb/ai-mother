package com.rush.rushaicodemother.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rush.rushaicodemother.ai.generation.LightweightCodeGenerationExecutor;
import com.rush.rushaicodemother.ai.guardrail.PromptSafetyInputGuardrail;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.ai.prompt.PromptSystemMessageTransformer;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.event.AiModelConfigChangedEvent;
import com.rush.rushaicodemother.model.event.AiModelCircuitOpenedEvent;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelInvocationCancellationBridge;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;

/**
 * AI 服务创建工厂。
 * <p>
 * 支持根据 {@link GenerationPerformanceProfile} 动态选择模型配置，
 * 实现首次生成加速和改修场景的平衡。
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class AiCodeGeneratorServiceFactory {

    private final ChatMemoryStore chatMemoryStore;

    private final ChatHistoryService chatHistoryService;

    private final StreamingModelFactory streamingModelFactory;

    private final PromptSystemMessageTransformer promptSystemMessageTransformer;

    private final GenerationModelInvocationCancellationBridge modelCancellationBridge;

    private final LightweightCodeGenerationExecutor lightweightCodeGenerationExecutor;

    private static final int DEFAULT_CHAT_MEMORY_MESSAGES = 20;

    /**
     * AI 服务实例缓存
     * 缓存策略：
     * - 最大缓存 1000 个实例
     * - 写入后 30 分钟过期
     * - 访问后 10 分钟过期
     */
    private final Cache<String, AiCodeGeneratorService> serviceCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .expireAfterAccess(Duration.ofMinutes(10))
            .removalListener((key, value, cause) -> {
                log.debug("AI 服务实例被移除，缓存键: {}, 原因: {}", key, cause);
            })
            .build();

    /**
     * 根据 appId 获取 HTML 默认生成服务。
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId) {
        return getAiCodeGeneratorService(appId, CodeGenTypeEnum.HTML, null);
    }

    /**
     * 根据 appId 获取服务
     *
     * @param appId       应用 id
     * @param codeGenType 生成类型
     * @return
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
        return getAiCodeGeneratorService(appId, codeGenType, null);
    }

    /**
     * 根据 appId 和性能配置获取服务。
     * <p>
     * 性能配置用于动态选择模型和工具调用策略，
     * 实现首次生成加速和改修场景的平衡。
     *
     * @param appId       应用 id
     * @param codeGenType 生成类型
     * @param profile     性能配置，null 表示使用默认配置
     * @return AI 服务实例
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId,
                                                             CodeGenTypeEnum codeGenType,
                                                             GenerationPerformanceProfile profile) {
        validateRequest(appId, codeGenType);
        // 性能配置不同时不能复用缓存
        String cacheKey = buildCacheKey(appId, codeGenType, profile);
        return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenType, profile));
    }

    /**
     * 为单个持久化任务创建独占 AI 服务，使模型回合和 provider 故障转移使用该任务自己的预算。
     * 任务级回调不能进入跨任务缓存，否则会把后续请求计入旧任务。
     */
    public AiCodeGeneratorService createTaskScopedAiCodeGeneratorService(
            long appId,
            CodeGenTypeEnum codeGenType,
            GenerationPerformanceProfile profile,
            Duration modelCallTimeout,
            Runnable beforeModelTurn,
            Runnable beforeProviderFailoverAttempt) {
        validateRequest(appId, codeGenType);
        StreamingChatModel executionModel = streamingModelFactory.createExecutionChatModel(
                modelCallTimeout, beforeModelTurn, beforeProviderFailoverAttempt);
        return createAiCodeGeneratorService(appId, codeGenType, profile, executionModel);
    }

    /**
     * 创建新的 AI 服务实例
     *
     * @param appId       应用 id
     * @param codeGenType 生成类型
     * @param profile     性能配置，null 表示使用默认配置
     * @return AI 服务实例
     */
    private AiCodeGeneratorService createAiCodeGeneratorService(long appId,
                                                                  CodeGenTypeEnum codeGenType,
                                                                  GenerationPerformanceProfile profile) {
        return createAiCodeGeneratorService(appId, codeGenType, profile, null);
    }

    /** 创建 AI 代码生成器服务。 */
    private AiCodeGeneratorService createAiCodeGeneratorService(
            long appId,
            CodeGenTypeEnum codeGenType,
            GenerationPerformanceProfile profile,
            StreamingChatModel executionModel) {
        log.info("为 appId: {} 创建新的 AI 服务实例, codeGenType={}, profile={}",
                appId, codeGenType, profile != null ? profile.modelTier() : "default");
        // 根据 appId 构建独立的对话记忆
        int maxMemoryMessages = DEFAULT_CHAT_MEMORY_MESSAGES;
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory
                .builder()
                .id(appId)
                .chatMemoryStore(chatMemoryStore)
                .maxMessages(maxMemoryMessages)
                .build();
        // 从数据库中加载对话历史到记忆中
        chatHistoryService.loadChatHistoryToMemory(
                appId, chatMemory, maxMemoryMessages);
        StreamingChatModel openAiStreamingChatModel = executionModel == null
                ? streamingModelFactory.createChatModel()
                : executionModel;
        ChatModel chatModel = streamingModelFactory.createPrimaryChatModel();
        return AiServices.builder(AiCodeGeneratorService.class)
                .chatModel(chatModel)
                .streamingChatModel(openAiStreamingChatModel)
                .systemMessageTransformer(promptSystemMessageTransformer::transform)
                .chatMemory(chatMemory)
                .registerListener(modelCancellationBridge.requestIssuedListener())
                .inputGuardrails(new PromptSafetyInputGuardrail())
                .build();
    }

    /**
 * 响应 AI 模型配置变更事件。
 *
 * @param event 待处理的领域事件
 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAiModelConfigChanged(AiModelConfigChangedEvent event) {
        log.info("检测到 AI 模型配置变更，清理 AI 服务缓存");
        serviceCache.invalidateAll();
    }

    /**
 * 响应 AI 模型熔断开启事件。
 *
 * @param event 待处理的领域事件
 */
    @EventListener
    public void onAiModelCircuitOpened(AiModelCircuitOpenedEvent event) {
        log.warn("模型熔断已开启，正在清理模型服务缓存，提供方={}，模型标识={}",
                event.provider(), event.modelId());
        serviceCache.invalidateAll();
    }

    private void validateRequest(long appId, CodeGenTypeEnum codeGenType) {
        ThrowUtils.throwIf(appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 必须大于 0");
        ThrowUtils.throwIf(codeGenType == null, ErrorCode.PARAMS_ERROR, "代码生成类型不能为空");
        ThrowUtils.throwIf(!lightweightCodeGenerationExecutor.supports(codeGenType),
                ErrorCode.PARAMS_ERROR,
                "当前生成类型未注册轻量代码生成协议");
    }

    /**
     * 构造缓存键（包含性能配置）
     */
    private String buildCacheKey(long appId, CodeGenTypeEnum codeGenType, GenerationPerformanceProfile profile) {
        String baseKey = appId + "_" + codeGenType.getValue();
        if (profile == null) {
            return baseKey + "_default";
        }
        return baseKey + "_" + profile.modelTier().name()
                + "_" + profile.thinkingEnabled()
                + "_tools_" + profile.maxToolInvocations();
    }
}
