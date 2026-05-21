package com.rush.rushaicodemother.ai;

import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI slot 填充服务工厂。
 */
@Slf4j
@Component
public class AiSlotFillServiceFactory {

    private final StreamingModelFactory streamingModelFactory;

    public AiSlotFillServiceFactory(StreamingModelFactory streamingModelFactory) {
        this.streamingModelFactory = streamingModelFactory;
    }

    /**
     * 创建 AI slot 填充服务实例。
     *
     * @return AI slot 填充服务
     */
    public AiSlotFillService createAiSlotFillService() {
        ChatModel chatModel = streamingModelFactory.createRoutingChatModel();
        return AiServices.builder(AiSlotFillService.class)
                .chatModel(chatModel)
                .build();
    }
}
