package com.rush.rushaicodemother.langgraph4j.ai;

import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.langgraph4j.tools.ImageSearchTool;
import com.rush.rushaicodemother.langgraph4j.tools.LogoGeneratorTool;
import com.rush.rushaicodemother.langgraph4j.tools.MermaidDiagramTool;
import com.rush.rushaicodemother.langgraph4j.tools.UndrawIllustrationTool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * 图片收集服务工厂
 */
@Slf4j
@Configuration
public class ImageCollectionServiceFactory {

    @Resource
    private StreamingModelFactory streamingModelFactory;

    @Resource
    private ImageSearchTool imageSearchTool;

    @Resource
    private UndrawIllustrationTool undrawIllustrationTool;

    @Resource
    private MermaidDiagramTool mermaidDiagramTool;

    @Resource
    private LogoGeneratorTool logoGeneratorTool;

    /**
     * 创建图片收集 AI 服务
     */
    @Bean
    @Scope("prototype")
    public ImageCollectionService createImageCollectionService() {
        ChatModel chatModel = streamingModelFactory.createPrimaryChatModel();
        return AiServices.builder(ImageCollectionService.class)
                .chatModel(chatModel)
                .tools(
                        imageSearchTool,
                        undrawIllustrationTool,
                        mermaidDiagramTool,
                        logoGeneratorTool
                )
                .build();
    }
}
