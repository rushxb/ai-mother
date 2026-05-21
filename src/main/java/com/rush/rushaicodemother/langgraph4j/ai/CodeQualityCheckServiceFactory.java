package com.rush.rushaicodemother.langgraph4j.ai;

import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * 代码质量检查服务工厂
 */
@Slf4j
@Configuration
public class CodeQualityCheckServiceFactory {

    @Resource
    private StreamingModelFactory streamingModelFactory;

    /**
     * 创建代码质量检查 AI 服务
     */
    @Bean
    @Scope("prototype")
    public CodeQualityCheckService createCodeQualityCheckService() {
        ChatModel chatModel = streamingModelFactory.createPrimaryChatModel();
        return AiServices.builder(CodeQualityCheckService.class)
                .chatModel(chatModel)
                .build();
    }
}
