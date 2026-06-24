package com.rush.rushaicodemother.ai;

import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * 提示词优化服务工厂
 */
@Configuration
public class PromptOptimizerServiceFactory {

    @Resource
    private StreamingModelFactory streamingModelFactory;

    /**
     * 创建提示词优化服务
     *
     * @return 提示词优化服务
     */
    @Bean
    @Scope("prototype")
    public PromptOptimizerService promptOptimizerService() {
        ChatModel chatModel = streamingModelFactory.createRoutingChatModel();
        return AiServices.builder(PromptOptimizerService.class)
                .chatModel(chatModel)
                .build();
    }
}
