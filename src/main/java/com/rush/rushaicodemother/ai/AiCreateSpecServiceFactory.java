package com.rush.rushaicodemother.ai;

import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.ai.prompt.PromptSystemMessageTransformer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Component;

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
        return AiServices.builder(AiCreateSpecService.class)
                .chatModel(chatModel)
                .systemMessageTransformer(promptSystemMessageTransformer::transform)
                .build();
    }
}
