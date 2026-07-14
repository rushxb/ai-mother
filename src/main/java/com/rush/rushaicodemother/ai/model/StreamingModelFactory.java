package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.monitor.AiModelMonitorListener;
import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeConfiguration;
import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

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

    private final AiModelMonitorListener aiModelMonitorListener;

    private final AiModelRuntimeService aiModelRuntimeService;

    private final AiModelRuntimeProperties runtimeProperties;

    private final OpenAiThinkingPolicy openAiThinkingPolicy;

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
        AiModelRuntimeConfiguration dbModel = getRequiredEnabledModelByType(
                modelType, "生成性能配置 " + profile.modelTier());
        log.info("使用数据库生成模型配置: provider={}, modelId={}, modelType={}, thinking={}",
                dbModel.provider(), dbModel.modelId(), dbModel.modelType(),
                profile.thinkingEnabled());
        return createModelFromDb(dbModel, profile.thinkingEnabled());
    }

    /**
     * 创建普通对话流式模型。
     * <p>
     * 从数据库读取 chat 类型模型。
     */
    public StreamingChatModel createChatModel() {
        AiModelRuntimeConfiguration dbModel = getRequiredEnabledModelByType(
                MODEL_TYPE_CHAT, "普通对话/快速流式任务");
        log.info("使用数据库快速流式模型配置: provider={}, modelId={}, modelType={}",
                dbModel.provider(), dbModel.modelId(), dbModel.modelType());
        return createModelFromDb(dbModel, false);
    }

    /**
     * 创建推理流式模型。
     * <p>
     * 从数据库读取 reasoning 类型模型。
     */
    public StreamingChatModel createReasoningModel() {
        AiModelRuntimeConfiguration dbModel = getRequiredEnabledModelByType(
                MODEL_TYPE_REASONING, "思考/重型生成任务");
        log.info("使用数据库思考流式模型配置: provider={}, modelId={}, modelType={}",
                dbModel.provider(), dbModel.modelId(), dbModel.modelType());
        return createModelFromDb(dbModel, true);
    }

    /**
     * 创建路由/轻量同步模型。
     * <p>
     * 使用数据库 chat 类型模型。
     */
    public ChatModel createRoutingChatModel() {
        AiModelRuntimeConfiguration dbModel = getRequiredEnabledModelByType(
                MODEL_TYPE_CHAT, "路由/意图/轻量同步任务");
        log.info("使用数据库快速同步模型配置: usage=routing, provider={}, modelId={}, modelType={}, timeoutSeconds={}, maxRetries={}",
                dbModel.provider(), dbModel.modelId(), dbModel.modelType(),
                runtimeProperties.getRoutingTimeout().toSeconds(), runtimeProperties.getRoutingMaxRetries());
        return createChatModelFromDb(
                dbModel,
                runtimeProperties.getRoutingTimeout(),
                runtimeProperties.getRoutingMaxRetries(),
                false
        );
    }

    /**
     * 创建 CREATE 创意规格同步模型。
     * <p>
     * 该调用只生成小型 JSON spec，不生成代码或大 patch，因此主链路可以保持短超时。
     */
    public ChatModel createCreateSpecChatModel() {
        AiModelRuntimeConfiguration dbModel = getRequiredEnabledModelByType(
                MODEL_TYPE_CHAT, "CREATE 创意规格生成任务");
        log.info("使用数据库 CREATE Spec 快速模型配置: provider={}, modelId={}, modelType={}, timeoutSeconds={}, maxRetries={}",
                dbModel.provider(), dbModel.modelId(), dbModel.modelType(),
                runtimeProperties.getCreateSpecTimeout().toSeconds(), 0);
        return createChatModelFromDb(dbModel, runtimeProperties.getCreateSpecTimeout(), 0, false);
    }

    /**
     * 创建主同步模型。
     * <p>
     * 使用数据库 reasoning 类型模型。
     */
    public ChatModel createPrimaryChatModel() {
        AiModelRuntimeConfiguration dbModel = getRequiredEnabledModelByType(
                MODEL_TYPE_REASONING, "主同步思考任务");
        log.info("使用数据库主思考模型配置: provider={}, modelId={}, modelType={}",
                dbModel.provider(), dbModel.modelId(), dbModel.modelType());
        return createChatModelFromDb(
                dbModel,
                null,
                null,
                true,
                runtimeProperties.isGenerationLogRequests(),
                runtimeProperties.isGenerationLogResponses()
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

    private AiModelRuntimeConfiguration getRequiredEnabledModelByType(String modelType, String usage) {
        try {
            return aiModelRuntimeService.requireRunnableModelByType(modelType);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("读取 {} 模型配置失败，usage={}", modelType, usage, LogExceptionSanitizer.sanitize(e));
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取 AI 模型配置失败");
        }
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
        var builder = OpenAiStreamingChatModel.builder()
                .apiKey(dbModel.apiKey())
                .baseUrl(dbModel.baseUrl())
                .modelName(dbModel.modelId())
                .temperature(resolveTemperature(dbModel))
                .logRequests(runtimeProperties.isGenerationLogRequests())
                .logResponses(runtimeProperties.isGenerationLogResponses())
                .timeout(runtimeProperties.getGenerationTimeout())
                .listeners(List.of(aiModelMonitorListener));

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

    private ChatModel createChatModelFromDb(AiModelRuntimeConfiguration dbModel,
                                            Duration timeout,
                                            Integer maxRetries,
                                            boolean enableThinking,
                                            boolean requestLogging,
                                            boolean responseLogging) {
        var builder = OpenAiChatModel.builder()
                .apiKey(dbModel.apiKey())
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

