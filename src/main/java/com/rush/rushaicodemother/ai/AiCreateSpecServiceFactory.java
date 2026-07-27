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

    public AiCreateSpecService createService() {
        ChatModel chatModel = streamingModelFactory.createCreateSpecChatModel();
        return createService(chatModel);
    }

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
