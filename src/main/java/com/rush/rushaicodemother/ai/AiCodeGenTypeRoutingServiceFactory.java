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
 * AI代码生成类型路由服务工厂
 *
 * @author rush
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AiCodeGenTypeRoutingServiceFactory {

    private final StreamingModelFactory streamingModelFactory;
    private final PromptSystemMessageTransformer promptSystemMessageTransformer;

    /**
     * 创建AI代码生成类型路由服务实例
     */
    public AiCodeGenTypeRoutingService createAiCodeGenTypeRoutingService() {
        ChatModel chatModel = streamingModelFactory.createRoutingChatModel();
        return createService(chatModel);
    }

    /** 创建具有调用方总超时且不执行 SDK 重试的路由服务。 */
    public AiCodeGenTypeRoutingService createAiCodeGenTypeRoutingService(Duration timeout) {
        ChatModel chatModel = streamingModelFactory.createRoutingChatModel(timeout, 0);
        return createService(chatModel);
    }

    /**
 * 创建执行 AI 代码生成类型路由服务。
 *
 * @param timeout 超时时间
 * @param beforeModelTurn 每轮模型调用前执行的回调
 * @param beforeProviderFailoverAttempt 模型提供方故障转移前执行的回调
 * @return 执行 AI 代码生成类型路由服务
 */
    public AiCodeGenTypeRoutingService createExecutionAiCodeGenTypeRoutingService(
            Duration timeout,
            Runnable beforeModelTurn,
            Runnable beforeProviderFailoverAttempt) {
        ChatModel chatModel = streamingModelFactory.createExecutionRoutingChatModel(
                timeout, beforeModelTurn, beforeProviderFailoverAttempt);
        return createService(chatModel);
    }

    private AiCodeGenTypeRoutingService createService(ChatModel chatModel) {
        return AiServices.builder(AiCodeGenTypeRoutingService.class)
                .chatModel(chatModel)
                .systemMessageTransformer(promptSystemMessageTransformer::transform)
                .build();
    }

    /**
     * 默认提供一个 Bean
     */
    @Bean
    @Scope("prototype")
    public AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService() {
        return createAiCodeGenTypeRoutingService();
    }
}
