package com.rush.rushaicodemother.ai;

import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.ai.prompt.PromptSystemMessageTransformer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.time.Duration;

/**
 * AI 代码编辑服务工厂。
 * 使用轻量路由模型进行代码编辑，避免占用主生成模型。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AiCodeEditServiceFactory {

    private final StreamingModelFactory streamingModelFactory;
    private final PromptSystemMessageTransformer promptSystemMessageTransformer;

    /**
     * 创建 AI 代码编辑服务实例。
     * 使用路由模型（轻量、快速），适合结构化编辑操作生成。
     */
    public AiCodeEditService createAiCodeEditService() {
        return createAiCodeEditService(null);
    }

    /** 创建一个编辑服务，其完整模型/故障转移请求受调用者限制。 */
    public AiCodeEditService createAiCodeEditService(Duration timeout) {
        ChatModel chatModel = timeout == null
                ? streamingModelFactory.createRoutingChatModel()
                : streamingModelFactory.createRoutingChatModel(timeout, 0);
        return createService(chatModel);
    }

    /** 创建受任务预算约束的同步编辑服务。 */
    public AiCodeEditService createExecutionAiCodeEditService(
            Duration timeout,
            Runnable beforeModelTurn,
            Runnable beforeProviderFailoverAttempt) {
        ChatModel chatModel = streamingModelFactory.createExecutionRoutingChatModel(
                timeout, beforeModelTurn, beforeProviderFailoverAttempt);
        return createService(chatModel);
    }

    private AiCodeEditService createService(ChatModel chatModel) {
        return AiServices.builder(AiCodeEditService.class)
                .chatModel(chatModel)
                .systemMessageTransformer(promptSystemMessageTransformer::transform)
                .build();
    }

    @Bean
    @Scope("prototype")
    public AiCodeEditService aiCodeEditService() {
        return createAiCodeEditService();
    }
}
