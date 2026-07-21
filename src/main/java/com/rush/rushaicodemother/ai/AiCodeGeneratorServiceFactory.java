package com.rush.rushaicodemother.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rush.rushaicodemother.ai.guardrail.PromptSafetyInputGuardrail;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.ai.prompt.PromptSystemMessageTransformer;
import com.rush.rushaicodemother.ai.tools.*;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.event.AiModelConfigChangedEvent;
import com.rush.rushaicodemother.model.event.AiModelCircuitOpenedEvent;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.orchestration.tool.AiToolInvocationPolicy;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionFailurePolicy;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
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

    private final ToolManager toolManager;

    private final StreamingModelFactory streamingModelFactory;

    private final ToolExecutionFailurePolicy toolExecutionFailurePolicy;

    private final AiToolInvocationPolicy aiToolInvocationPolicy;

    private final PromptSystemMessageTransformer promptSystemMessageTransformer;

    private static final int DEFAULT_CHAT_MEMORY_MESSAGES = 20;
    private static final int HEAVY_PROJECT_MEMORY_MESSAGES = 8;
    private static final int HEAVY_PROJECT_INITIAL_HISTORY_MESSAGES = 4;

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
        log.info("为 appId: {} 创建新的 AI 服务实例, codeGenType={}, profile={}",
                appId, codeGenType, profile != null ? profile.modelTier() : "default");
        // 根据 appId 构建独立的对话记忆
        int maxMemoryMessages = resolveMemoryMessageCount(codeGenType);
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory
                .builder()
                .id(appId)
                .chatMemoryStore(chatMemoryStore)
                .maxMessages(maxMemoryMessages)
                .build();
        // 从数据库中加载对话历史到记忆中
        chatHistoryService.loadChatHistoryToMemory(
                appId, chatMemory, resolveInitialHistoryMessageCount(codeGenType, maxMemoryMessages));
        return switch (codeGenType) {
            // Vue、后端和全栈项目生成，使用工具调用和推理模型
            case VUE_PROJECT, BACKEND_PROJECT, FULL_STACK_PROJECT -> {
                // 根据性能配置选择流式模型
                StreamingChatModel streamingModel = selectStreamingModel(codeGenType, profile);
                ChatModel chatModel = streamingModelFactory.createPrimaryChatModel();
                int maxToolInvocations = resolveMaxToolInvocations(codeGenType, profile);
                yield AiServices.builder(AiCodeGeneratorService.class)
                        .chatModel(chatModel)
                        .streamingChatModel(streamingModel)
                        .systemMessageTransformer(promptSystemMessageTransformer::transform)
                        .chatMemoryProvider(memoryId -> chatMemory)
                        .tools((Object[]) toolManager.getToolsForCodeGen(codeGenType))
                        .beforeToolExecution(event ->
                                aiToolInvocationPolicy.authorize(event, codeGenType, profile))
                        .afterToolExecution(toolExecution ->
                                aiToolInvocationPolicy.clearActiveInvocation())
                        // 处理工具调用幻觉问题
                        .hallucinatedToolNameStrategy(toolExecutionRequest ->
                                ToolExecutionResultMessage.from(toolExecutionRequest,
                                        "Error: there is no tool called " + toolExecutionRequest.name())
                        )
                        .toolExecutionErrorHandler((failure, context) ->
                                toolExecutionFailurePolicy.handle(failure, context, codeGenType, profile))
                        .maxToolCallingRoundTrips(maxToolInvocations)
                        .inputGuardrails(new PromptSafetyInputGuardrail()) // 添加输入护轨
                        .build();
            }
            // HTML 和 多文件生成，使用流式对话模型
            case HTML, MULTI_FILE -> {
                StreamingChatModel openAiStreamingChatModel = streamingModelFactory.createChatModel();
                ChatModel chatModel = streamingModelFactory.createPrimaryChatModel();
                yield AiServices.builder(AiCodeGeneratorService.class)
                        .chatModel(chatModel)
                        .streamingChatModel(openAiStreamingChatModel)
                        .systemMessageTransformer(promptSystemMessageTransformer::transform)
                        .chatMemory(chatMemory)
                        .inputGuardrails(new PromptSafetyInputGuardrail()) // 添加输入护轨
                        .build();
            }
            default ->
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型: " + codeGenType.getValue());
        };
    }

    /**
     * 根据性能配置选择流式模型。
     * <p>
     * 未指定性能配置时使用默认推理模型。
     */
    private StreamingChatModel selectStreamingModel(CodeGenTypeEnum codeGenType,
                                                     GenerationPerformanceProfile profile) {
        if (profile == null) {
            return streamingModelFactory.createReasoningModel();
        }
        return streamingModelFactory.createModel(profile);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAiModelConfigChanged(AiModelConfigChangedEvent event) {
        log.info("检测到 AI 模型配置变更，清理 AI 服务缓存");
        serviceCache.invalidateAll();
    }

    @EventListener
    public void onAiModelCircuitOpened(AiModelCircuitOpenedEvent event) {
        log.warn("AI model circuit opened; invalidating cached AI services, provider={}, modelId={}",
                event.provider(), event.modelId());
        serviceCache.invalidateAll();
    }


    /**
     * 解析最大工具调用次数。
     * <p>
     * 优先使用性能配置的值，否则按生成类型使用默认工具调用上限。
     */
    private int resolveMaxToolInvocations(CodeGenTypeEnum codeGenType,
                                           GenerationPerformanceProfile profile) {
        if (profile != null) {
            return profile.maxToolInvocations();
        }
        return switch (codeGenType) {
            case FULL_STACK_PROJECT -> 32;
            case BACKEND_PROJECT -> 20;
            default -> 10;
        };
    }

    private int resolveMemoryMessageCount(CodeGenTypeEnum codeGenType) {
        return switch (codeGenType) {
            case VUE_PROJECT, BACKEND_PROJECT, FULL_STACK_PROJECT -> HEAVY_PROJECT_MEMORY_MESSAGES;
            default -> DEFAULT_CHAT_MEMORY_MESSAGES;
        };
    }

    private int resolveInitialHistoryMessageCount(CodeGenTypeEnum codeGenType, int memoryMessageCount) {
        return switch (codeGenType) {
            case VUE_PROJECT, BACKEND_PROJECT, FULL_STACK_PROJECT ->
                    Math.min(memoryMessageCount, HEAVY_PROJECT_INITIAL_HISTORY_MESSAGES);
            default -> memoryMessageCount;
        };
    }

    private void validateRequest(long appId, CodeGenTypeEnum codeGenType) {
        ThrowUtils.throwIf(appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 必须大于 0");
        ThrowUtils.throwIf(codeGenType == null, ErrorCode.PARAMS_ERROR, "代码生成类型不能为空");
    }

    /**
     * 构造缓存键（包含性能配置）
     */
    private String buildCacheKey(long appId, CodeGenTypeEnum codeGenType, GenerationPerformanceProfile profile) {
        String baseKey = appId + "_" + codeGenType.getValue();
        if (profile == null) {
            return baseKey + "_default";
        }
        return baseKey + "_" + profile.modelTier().name() + "_" + profile.thinkingEnabled();
    }
}
