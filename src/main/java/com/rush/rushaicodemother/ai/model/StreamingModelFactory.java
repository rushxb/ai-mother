package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.monitor.AiModelMonitorListener;
import dev.langchain4j.model.chat.StreamingChatModel;
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

    /**
     * 根据性能配置创建流式模型。
     *
     * @param profile 性能配置
     * @return 流式模型实例
     */
    public StreamingChatModel createModel(GenerationPerformanceProfile profile) {
        return switch (profile.modelTier()) {
            case SPEED, BALANCED -> createFlashModel(profile.thinkingEnabled());
            case QUALITY -> createReasoningModel(profile.thinkingEnabled());
        };
    }

    /**
     * 创建轻量 Flash 模型。
     * <p>
     * 用于首次简单生成和改修场景，响应速度快。
     */
    private StreamingChatModel createFlashModel(boolean enableThinking) {
        log.debug("创建 Flash 模型, thinking={}", enableThinking);

        // Flash 模型默认禁用 thinking，除非明确要求
        if (!enableThinking) {
            return OpenAiStreamingChatModel.builder()
                    .apiKey(flashApiKey)
                    .baseUrl(flashBaseUrl)
                    .modelName(flashModelName)
                    .maxTokens(flashMaxTokens)
                    .temperature(flashTemperature)
                    .logRequests(logRequests)
                    .logResponses(logResponses)
                    .listeners(List.of(aiModelMonitorListener))
                    .disableThinkingForDeepSeekV4(true)
                    .build();
        }

        return OpenAiStreamingChatModel.builder()
                .apiKey(flashApiKey)
                .baseUrl(flashBaseUrl)
                .modelName(flashModelName)
                .maxTokens(flashMaxTokens)
                .temperature(flashTemperature)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .listeners(List.of(aiModelMonitorListener))
                .build();
    }

    /**
     * 创建推理 Reasoning 模型。
     * <p>
     * 用于复杂任务，开启 thinking 以获得更好的推理能力。
     */
    private StreamingChatModel createReasoningModel(boolean enableThinking) {
        log.debug("创建 Reasoning 模型, thinking={}", enableThinking);

        if (enableThinking) {
            return OpenAiStreamingChatModel.builder()
                    .apiKey(reasoningApiKey)
                    .baseUrl(reasoningBaseUrl)
                    .modelName(reasoningModelName)
                    .maxTokens(reasoningMaxTokens)
                    .temperature(reasoningTemperature)
                    .logRequests(logRequests)
                    .logResponses(logResponses)
                    .listeners(List.of(aiModelMonitorListener))
                    .enableThinkingForDeepSeekV4(true)
                    .build();
        }

        return OpenAiStreamingChatModel.builder()
                .apiKey(reasoningApiKey)
                .baseUrl(reasoningBaseUrl)
                .modelName(reasoningModelName)
                .maxTokens(reasoningMaxTokens)
                .temperature(reasoningTemperature)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .listeners(List.of(aiModelMonitorListener))
                .disableThinkingForDeepSeekV4(true)
                .build();
    }
}
