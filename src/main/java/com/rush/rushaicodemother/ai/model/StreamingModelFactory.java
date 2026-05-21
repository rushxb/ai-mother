package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.model.entity.AiModel;
import com.rush.rushaicodemother.monitor.AiModelMonitorListener;
import com.rush.rushaicodemother.service.AiModelService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 流式模型工厂。
 * <p>
 * 根据 {@link GenerationPerformanceProfile} 动态创建不同配置的流式模型，
 * 实现模型选择策略的解耦。
 */
@Slf4j
@Component
public class StreamingModelFactory {

    @Resource
    private AiModelMonitorListener aiModelMonitorListener;

    @Resource
    private AiModelService aiModelService;

    @Value("${langchain4j.open-ai.reasoning-streaming-chat-model.base-url}")
    private String reasoningBaseUrl;

    @Value("${langchain4j.open-ai.reasoning-streaming-chat-model.api-key}")
    private String reasoningApiKey;

    @Value("${langchain4j.open-ai.reasoning-streaming-chat-model.model-name}")
    private String reasoningModelName;

    @Value("${langchain4j.open-ai.reasoning-streaming-chat-model.max-tokens}")
    private Integer reasoningMaxTokens;

    @Value("${langchain4j.open-ai.reasoning-streaming-chat-model.temperature}")
    private Double reasoningTemperature;

    @Value("${langchain4j.open-ai.routing-chat-model.base-url}")
    private String routingBaseUrl;

    @Value("${langchain4j.open-ai.routing-chat-model.api-key}")
    private String routingApiKey;

    @Value("${langchain4j.open-ai.routing-chat-model.model-name}")
    private String routingModelName;

    @Value("${langchain4j.open-ai.routing-chat-model.max-tokens}")
    private Integer routingMaxTokens;

    @Value("${langchain4j.open-ai.routing-chat-model.temperature:#{null}}")
    private Double routingTemperature;

    @Value("${langchain4j.open-ai.streaming-chat-model.base-url}")
    private String flashBaseUrl;

    @Value("${langchain4j.open-ai.streaming-chat-model.api-key}")
    private String flashApiKey;

    @Value("${langchain4j.open-ai.streaming-chat-model.model-name}")
    private String flashModelName;

    @Value("${langchain4j.open-ai.streaming-chat-model.max-tokens}")
    private Integer flashMaxTokens;

    @Value("${langchain4j.open-ai.streaming-chat-model.temperature:#{null}}")
    private Double flashTemperature;

    @Value("${langchain4j.open-ai.reasoning-streaming-chat-model.log-requests:false}")
    private boolean logRequests;

    @Value("${langchain4j.open-ai.reasoning-streaming-chat-model.log-responses:false}")
    private boolean logResponses;

    @Value("${langchain4j.open-ai.routing-chat-model.log-requests:false}")
    private boolean routingLogRequests;

    @Value("${langchain4j.open-ai.routing-chat-model.log-responses:false}")
    private boolean routingLogResponses;

    /**
     * 根据性能配置创建流式模型。
     * <p>
     * 优先从数据库读取模型配置，如果数据库中没有配置，则使用配置文件的默认配置。
     *
     * @param profile 性能配置
     * @return 流式模型实例
     */
    public StreamingChatModel createModel(GenerationPerformanceProfile profile) {
        String modelType = resolveModelType(profile.modelTier());
        AiModel dbModel = getPreferredEnabledModel(modelType);

        if (dbModel != null) {
            log.debug("使用数据库模型配置: {}", dbModel.getModelName());
            return createModelFromDb(dbModel, profile.thinkingEnabled());
        }

        // 回退到配置文件默认配置
        log.debug("使用配置文件默认模型配置");
        return switch (profile.modelTier()) {
            case SPEED, BALANCED -> createFlashModel(profile.thinkingEnabled());
            case QUALITY -> createReasoningModel(profile.thinkingEnabled());
        };
    }

    /**
     * 创建普通对话流式模型。
     * <p>
     * 优先从数据库读取 chat 类型模型，找不到时回退到配置文件默认模型。
     */
    public StreamingChatModel createChatModel() {
        AiModel dbModel = getPreferredEnabledModel("chat");
        if (dbModel != null) {
            log.debug("使用数据库普通模型配置: {}", dbModel.getModelName());
            return createModelFromDb(dbModel, false);
        }
        log.debug("使用配置文件默认普通模型配置");
        return createFlashModel(false);
    }

    /**
     * 创建推理流式模型。
     * <p>
     * 优先从数据库读取 reasoning 类型模型，找不到时回退到配置文件默认推理模型。
     */
    public StreamingChatModel createReasoningModel() {
        AiModel dbModel = getPreferredEnabledModel("reasoning");
        if (dbModel != null) {
            log.debug("使用数据库推理模型配置: {}", dbModel.getModelName());
            return createModelFromDb(dbModel, true);
        }
        log.debug("使用配置文件默认推理模型配置");
        return createReasoningModel(true);
    }

    /**
     * 创建路由/轻量同步模型。
     * <p>
     * 优先使用数据库 chat 类型模型，找不到时降级到配置文件中的 routing-chat-model。
     */
    public ChatModel createRoutingChatModel() {
        AiModel dbModel = getPreferredEnabledModel("chat");
        if (dbModel != null) {
            log.debug("使用数据库路由模型配置: {}", dbModel.getModelName());
            return createChatModelFromDb(dbModel);
        }
        log.debug("使用配置文件默认路由模型配置");
        return OpenAiChatModel.builder()
                .apiKey(routingApiKey)
                .baseUrl(routingBaseUrl)
                .modelName(routingModelName)
                .maxTokens(routingMaxTokens)
                .temperature(routingTemperature)
                .logRequests(routingLogRequests)
                .logResponses(routingLogResponses)
                .build();
    }

    /**
     * 创建主同步模型。
     * <p>
     * 优先使用数据库 reasoning 类型模型，其次 chat 类型模型，最后降级到配置文件。
     */
    public ChatModel createPrimaryChatModel() {
        AiModel dbModel = getPreferredEnabledModel("reasoning");
        if (dbModel != null) {
            log.debug("使用数据库主模型配置: {}", dbModel.getModelName());
            return createChatModelFromDb(dbModel);
        }
        log.debug("使用配置文件默认主模型配置");
        return OpenAiChatModel.builder()
                .apiKey(reasoningApiKey)
                .baseUrl(reasoningBaseUrl)
                .modelName(reasoningModelName)
                .maxTokens(reasoningMaxTokens)
                .temperature(reasoningTemperature)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }

    /**
     * 根据模型层级解析模型类型
     */
    private String resolveModelType(GenerationPerformanceProfile.ModelTier modelTier) {
        return switch (modelTier) {
            case SPEED, BALANCED -> "chat";
            case QUALITY -> "reasoning";
        };
    }

    /**
     * 获取指定类型的第一个启用模型
     */
    private AiModel getFirstEnabledModelByType(String modelType) {
        try {
            List<AiModel> models = aiModelService.listEnabledModelsByType(modelType);
            return models.isEmpty() ? null : models.get(0);
        } catch (Exception e) {
            log.warn("从数据库获取模型配置失败，将使用默认配置", e);
            return null;
        }
    }

    /**
     * 获取优先类型的启用模型；如果没有该类型，使用任意启用模型。
     */
    private AiModel getPreferredEnabledModel(String preferredModelType) {
        AiModel preferredModel = getFirstEnabledModelByType(preferredModelType);
        if (preferredModel != null) {
            return preferredModel;
        }
        try {
            List<AiModel> models = aiModelService.listEnabledModels();
            return models.isEmpty() ? null : models.get(0);
        } catch (Exception e) {
            log.warn("从数据库获取启用模型配置失败，将使用默认配置", e);
            return null;
        }
    }

    /**
     * 从数据库配置创建模型
     * <p>
     * 兼容性处理：
     * 1. 只有 supportsThinking=1 的模型才发送 thinking 参数
     * 2. temperature 范围根据模型提供商调整
     */
    private StreamingChatModel createModelFromDb(AiModel dbModel, boolean enableThinking) {
        var builder = OpenAiStreamingChatModel.builder()
                .apiKey(dbModel.getApiKey())
                .baseUrl(dbModel.getBaseUrl())
                .modelName(dbModel.getModelId())
                .maxTokens(dbModel.getMaxTokens())
                .temperature(resolveTemperature(dbModel))
                .logRequests(logRequests)
                .logResponses(logResponses)
                .listeners(List.of(aiModelMonitorListener));

        // 只有明确标记支持 thinking 的模型才设置 thinking 参数
        // 注意：langchain4j 1.1.0 暂不支持 thinking API，预留注释
        // if (dbModel.getSupportsThinking() != null && dbModel.getSupportsThinking() == 1) {
        //     builder.thinking(enableThinking ? Thinking.enabled() : Thinking.disabled());
        // }

        return builder.build();
    }

    private ChatModel createChatModelFromDb(AiModel dbModel) {
        return OpenAiChatModel.builder()
                .apiKey(dbModel.getApiKey())
                .baseUrl(dbModel.getBaseUrl())
                .modelName(dbModel.getModelId())
                .maxTokens(dbModel.getMaxTokens())
                .temperature(resolveTemperature(dbModel))
                .logRequests(routingLogRequests)
                .logResponses(routingLogResponses)
                .build();
    }

    /**
     * 判断是否为支持 thinking 的模型
     * 支持 thinking 的模型包括：
     * - DeepSeek V4 系列 (deepseek-v4-*)
     * - OpenAI o1/o3 系列
     * - Claude 3.5 Sonnet / Claude 3 Opus
     */
    private boolean isThinkingCapableModel(String modelId) {
        if (modelId == null) return false;
        String lower = modelId.toLowerCase();
        
        // DeepSeek V4 系列
        if (lower.startsWith("deepseek-v4-")) return true;
        
        // OpenAI o1/o3 系列
        if (lower.startsWith("o1") || lower.startsWith("o3")) return true;
        
        // Claude 3.5 Sonnet / Claude 3 Opus
        if (lower.contains("claude-3-5-sonnet") || lower.contains("claude-3-opus")) return true;
        
        return false;
    }

    /**
     * 解析温度参数，确保在模型支持的范围内
     * <p>
     * 不同模型的 temperature 范围：
     * - DeepSeek: 0-2
     * - OpenAI: 0-2
     * - Claude: 0-1
     * - 通义千问: 0-2
     * - 其他: 默认 0-1（安全范围）
     */
    private Double resolveTemperature(AiModel dbModel) {
        Double temp = dbModel.getTemperature();
        if (temp == null) {
            return 0.7;
        }

        String modelId = dbModel.getModelId() != null ? dbModel.getModelId().toLowerCase() : "";
        String provider = dbModel.getProvider() != null ? dbModel.getProvider().toLowerCase() : "";

        // Claude 模型限制 temperature 在 0-1 范围
        if (modelId.contains("claude") || provider.contains("anthropic")) {
            return Math.min(temp, 1.0);
        }

        // 其他模型使用原始值（OpenAI/DeepSeek/通义千问都支持 0-2）
        return temp;
    }

    /**
     * 创建轻量 Flash 模型。
     * <p>
     * 用于首次简单生成和改修场景，响应速度快。
     * 注意：此方法使用配置文件中的默认配置。
     */
    private StreamingChatModel createFlashModel(boolean enableThinking) {
        log.debug("创建 Flash 模型, thinking={}", enableThinking);

        var builder = OpenAiStreamingChatModel.builder()
                .apiKey(flashApiKey)
                .baseUrl(flashBaseUrl)
                .modelName(flashModelName)
                .maxTokens(flashMaxTokens)
                .temperature(flashTemperature)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .listeners(List.of(aiModelMonitorListener));

        // 支持 thinking 的模型设置 thinking 参数
        // 注意：langchain4j 1.1.0 暂不支持 thinking API，预留注释
        // if (isThinkingCapableModel(flashModelName)) {
        //     builder.thinking(enableThinking ? Thinking.enabled() : Thinking.disabled());
        // }

        return builder.build();
    }

    /**
     * 创建推理 Reasoning 模型。
     * <p>
     * 用于复杂任务，开启 thinking 以获得更好的推理能力。
     * 注意：此方法使用配置文件中的默认配置。
     */
    private StreamingChatModel createReasoningModel(boolean enableThinking) {
        log.debug("创建 Reasoning 模型, thinking={}", enableThinking);

        var builder = OpenAiStreamingChatModel.builder()
                .apiKey(reasoningApiKey)
                .baseUrl(reasoningBaseUrl)
                .modelName(reasoningModelName)
                .maxTokens(reasoningMaxTokens)
                .temperature(reasoningTemperature)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .listeners(List.of(aiModelMonitorListener));

        // 支持 thinking 的模型设置 thinking 参数
        // 注意：langchain4j 1.1.0 暂不支持 thinking API，预留注释
        // if (isThinkingCapableModel(reasoningModelName)) {
        //     builder.thinking(enableThinking ? Thinking.enabled() : Thinking.disabled());
        // }

        return builder.build();
    }
}
