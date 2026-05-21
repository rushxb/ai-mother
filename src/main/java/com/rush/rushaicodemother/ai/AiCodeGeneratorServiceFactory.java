package com.rush.rushaicodemother.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rush.rushaicodemother.ai.guardrail.PromptSafetyInputGuardrail;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.ai.tools.*;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.event.AiModelConfigChangedEvent;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.AiModelService;
import com.rush.rushaicodemother.service.ChatHistoryService;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.time.Duration;

/**
 * AI 服务创建工厂。
 * <p>
 * 支持根据 {@link GenerationPerformanceProfile} 动态选择模型配置，
 * 实现首次生成加速和改修场景的平衡。
 */
@Configuration
@Slf4j
public class AiCodeGeneratorServiceFactory {

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private ToolManager toolManager;

    @Resource
    private StreamingModelFactory streamingModelFactory;

    @Resource
    private AiModelService aiModelService;

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
     * 根据 appId 获取服务（为了兼容老逻辑）
     *
     * @param appId
     * @return
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId) {
        return getAiCodeGeneratorService(appId, CodeGenTypeEnum.HTML);
    }

    /**
     * 根据 appId 获取服务
     *
     * @param appId       应用 id
     * @param codeGenType 生成类型
     * @return
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
        String cacheKey = buildCacheKey(appId, codeGenType);
        return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenType, null));
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
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory
                .builder()
                .id(appId)
                .chatMemoryStore(redisChatMemoryStore)
                .maxMessages(20)
                .build();
        // 从数据库中加载对话历史到记忆中
        chatHistoryService.loadChatHistoryToMemory(appId, chatMemory, 20);
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
                        .chatMemoryProvider(memoryId -> chatMemory)
                        .tools((Object[]) toolManager.getToolsForCodeGen(codeGenType))
                        // 处理工具调用幻觉问题
                        .hallucinatedToolNameStrategy(toolExecutionRequest ->
                                ToolExecutionResultMessage.from(toolExecutionRequest,
                                        "Error: there is no tool called " + toolExecutionRequest.name())
                        )
                        .maxSequentialToolsInvocations(maxToolInvocations)
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
     * 如果没有性能配置或配置为 null，使用默认的推理模型（向后兼容）。
     */
    private StreamingChatModel selectStreamingModel(CodeGenTypeEnum codeGenType,
                                                     GenerationPerformanceProfile profile) {
        if (profile == null) {
            return streamingModelFactory.createReasoningModel();
        }
        return streamingModelFactory.createModel(profile);
    }

    @EventListener
    public void onAiModelConfigChanged(AiModelConfigChangedEvent event) {
        log.info("检测到 AI 模型配置变更，清理 AI 服务缓存");
        aiModelService.evictEnabledModelCache();
        serviceCache.invalidateAll();
    }


    /**
     * 解析最大工具调用次数。
     * <p>
     * 优先使用性能配置的值，否则使用默认值（向后兼容）。
     */
    private int resolveMaxToolInvocations(CodeGenTypeEnum codeGenType,
                                           GenerationPerformanceProfile profile) {
        if (profile != null) {
            return profile.maxToolInvocations();
        }
        // 向后兼容：使用默认值
        return switch (codeGenType) {
            case FULL_STACK_PROJECT -> 32;
            case BACKEND_PROJECT -> 20;
            default -> 10;
        };
    }

    /**
     * 创建 AI 代码生成器服务
     *
     * @return
     */
    @Bean
    public AiCodeGeneratorService aiCodeGeneratorService() {
        return getAiCodeGeneratorService(0);
    }

    /**
     * 构造缓存键
     */
    private String buildCacheKey(long appId, CodeGenTypeEnum codeGenType) {
        return buildCacheKey(appId, codeGenType, null);
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
