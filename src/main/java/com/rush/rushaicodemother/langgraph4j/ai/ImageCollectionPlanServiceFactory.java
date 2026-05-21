package com.rush.rushaicodemother.langgraph4j.ai;

import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * 图片收集规划服务工厂
 */
@Configuration
public class ImageCollectionPlanServiceFactory {

    @Resource
    private StreamingModelFactory streamingModelFactory;

    @Bean
    @Scope("prototype")
    public ImageCollectionPlanService createImageCollectionPlanService() {
        ChatModel chatModel = streamingModelFactory.createPrimaryChatModel();
        return AiServices.builder(ImageCollectionPlanService.class)
                .chatModel(chatModel)
                .build();
    }
}
