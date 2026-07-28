package com.rush.rushaicodemother.ai;

import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.ai.prompt.PromptSystemMessageTransformer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 创建规格Service对象工厂。
 */
@Component
public class AiCreateSpecServiceFactory {

    private final StreamingModelFactory streamingModelFactory;
    private final PromptSystemMessageTransformer promptSystemMessageTransformer;

    public AiCreateSpecServiceFactory(StreamingModelFactory streamingModelFactory,
                                      PromptSystemMessageTransformer promptSystemMessageTransformer) {
        this.streamingModelFactory = streamingModelFactory;
        this.promptSystemMessageTransformer = promptSystemMessageTransformer;
    }

    /**
 * 创建服务。
 *
 * @return 服务
 */
    public AiCreateSpecService createService() {
        ChatModel chatModel = streamingModelFactory.createCreateSpecChatModel();
        return createService(chatModel);
    }

    /**
 * 创建执行服务。
 *
 * @param timeout 超时时间
 * @param beforeModelTurn 每轮模型调用前执行的回调
 * @param beforeProviderFailoverAttempt 模型提供方故障转移前执行的回调
 * @return 执行服务
 */
    public AiCreateSpecService createExecutionService(Duration timeout,
                                                      Runnable beforeModelTurn,
                                                      Runnable beforeProviderFailoverAttempt) {
        ChatModel chatModel = streamingModelFactory.createExecutionCreateSpecChatModel(
                timeout, beforeModelTurn, beforeProviderFailoverAttempt);
        return createService(chatModel);
    }

    private AiCreateSpecService createService(ChatModel chatModel) {
        return AiServices.builder(AiCreateSpecService.class)
                .chatModel(chatModel)
                .systemMessageTransformer(promptSystemMessageTransformer::transform)
                .build();
    }
}
