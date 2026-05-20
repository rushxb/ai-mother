package com.rush.rushaicodemother.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * AI slot 填充服务工厂。
 */
@Slf4j
@Component
public class AiSlotFillServiceFactory {

    private final ChatModel routingChatModelPrototype;

    public AiSlotFillServiceFactory(@Qualifier("routingChatModelPrototype") ChatModel routingChatModelPrototype) {
        this.routingChatModelPrototype = routingChatModelPrototype;
    }

    /**
     * 创建 AI slot 填充服务实例。
     *
     * @return AI slot 填充服务
     */
    public AiSlotFillService createAiSlotFillService() {
        return AiServices.builder(AiSlotFillService.class)
                .chatModel(routingChatModelPrototype)
                .build();
    }
}
