package com.rush.rushaicodemother.ai;

import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * 应用标题生成服务工厂
 */
@Configuration
public class AppNameGeneratorServiceFactory {

    @Resource
    private StreamingModelFactory streamingModelFactory;

    /**
     * 创建应用标题生成服务实例
     */
    public AppNameGeneratorService createAppNameGeneratorService() {
        ChatModel chatModel = streamingModelFactory.createRoutingChatModel();
        return AiServices.builder(AppNameGeneratorService.class)
                .chatModel(chatModel)
                .build();
    }

    /**
     * 默认提供一个 Bean
     */
    @Bean
    @Scope("prototype")
    public AppNameGeneratorService appNameGeneratorService() {
        return createAppNameGeneratorService();
    }
}
