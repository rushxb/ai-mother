package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.monitor.AiModelMonitorListener;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.reasoning-streaming-chat-model")
@Data
public class ReasoningStreamingChatModelConfig {

    @Resource
    private AiModelMonitorListener aiModelMonitorListener;

    private String baseUrl;

    private String apiKey;

    private String modelName;

    private Integer maxTokens;

    private Double temperature;

    private Boolean logRequests = false;

    private Boolean logResponses = false;

    /**
     * 判断是否支持 thinking 的模型
     */
    private boolean isThinkingCapableModel(String modelId) {
        if (modelId == null) return false;
        String lower = modelId.toLowerCase();
        return lower.startsWith("deepseek-v4-") 
                || lower.startsWith("o1") 
                || lower.startsWith("o3")
                || lower.contains("claude-3-5-sonnet")
                || lower.contains("claude-3-opus");
    }

    /**
     * 推理流式模型（用于 Vue 项目生成，带工具调用）
     */
    @Bean
    @Scope("prototype")
    public StreamingChatModel reasoningStreamingChatModelPrototype() {
        var builder = OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .listeners(List.of(aiModelMonitorListener));

        // 支持 thinking 的模型默认启用 thinking
        // 注意：langchain4j 1.1.0 暂不支持 thinking API
        // if (isThinkingCapableModel(modelName)) {
        //     builder.thinking(Thinking.enabled());
        // }

        return builder.build();
    }
}
