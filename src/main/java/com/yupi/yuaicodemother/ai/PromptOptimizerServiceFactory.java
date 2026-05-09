package com.yupi.yuaicodemother.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提示词优化服务工厂
 */
@Configuration
public class PromptOptimizerServiceFactory {

    @Resource(name = "openAiChatModel")
    private ChatModel chatModel;

    /**
     * 创建提示词优化服务
     *
     * @return 提示词优化服务
     */
    @Bean
    public PromptOptimizerService promptOptimizerService() {
        return AiServices.builder(PromptOptimizerService.class)
                .chatModel(chatModel)
                .build();
    }
}
