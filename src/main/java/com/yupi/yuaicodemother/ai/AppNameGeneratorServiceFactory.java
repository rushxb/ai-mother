package com.yupi.yuaicodemother.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用标题生成服务工厂
 */
@Configuration
public class AppNameGeneratorServiceFactory {

    @Resource
    private ApplicationContext applicationContext;

    /**
     * 创建应用标题生成服务实例
     */
    public AppNameGeneratorService createAppNameGeneratorService() {
        ChatModel chatModel = applicationContext.getBean("routingChatModelPrototype", ChatModel.class);
        return AiServices.builder(AppNameGeneratorService.class)
                .chatModel(chatModel)
                .build();
    }

    /**
     * 默认提供一个 Bean
     */
    @Bean
    public AppNameGeneratorService appNameGeneratorService() {
        return createAppNameGeneratorService();
    }
}
