package com.rush.rushaicodemother.ai;

import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Component;

@Component
public class AiCreateSpecServiceFactory {

    private final StreamingModelFactory streamingModelFactory;

    public AiCreateSpecServiceFactory(StreamingModelFactory streamingModelFactory) {
        this.streamingModelFactory = streamingModelFactory;
    }

    public AiCreateSpecService createService() {
        ChatModel chatModel = streamingModelFactory.createCreateSpecChatModel();
        return AiServices.builder(AiCreateSpecService.class)
                .chatModel(chatModel)
                .build();
    }
}
