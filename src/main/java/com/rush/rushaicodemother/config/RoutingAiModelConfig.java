package com.rush.rushaicodemother.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * 智能路由模型配置
 */
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.routing-chat-model")
@Data
public class RoutingAiModelConfig {

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
     * 创建用于路由判断的ChatModel
     */
    @Bean
    @Scope("prototype")
    public ChatModel routingChatModelPrototype() {
        var builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(logRequests)
                .logResponses(logResponses);

        // 支持 thinking 的模型默认禁用 thinking
        // 注意：langchain4j 1.1.0 暂不支持 thinking API
        // if (isThinkingCapableModel(modelName)) {
        //     builder.thinking(Thinking.disabled());
        // }

        return builder.build();
    }
}
