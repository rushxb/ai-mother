package com.rush.rushaicodemother.ai;

import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * AI 代码编辑服务工厂。
 * 使用轻量路由模型进行代码编辑，避免占用主生成模型。
 */
@Slf4j
@Configuration
public class AiCodeEditServiceFactory {

    @Resource
    private StreamingModelFactory streamingModelFactory;

    /**
     * 创建 AI 代码编辑服务实例。
     * 使用路由模型（轻量、快速），适合结构化编辑操作生成。
     */
    public AiCodeEditService createAiCodeEditService() {
        ChatModel chatModel = streamingModelFactory.createRoutingChatModel();
        return AiServices.builder(AiCodeEditService.class)
                .chatModel(chatModel)
                .build();
    }

    @Bean
    @Scope("prototype")
    public AiCodeEditService aiCodeEditService() {
        return createAiCodeEditService();
    }
}
