package com.rush.rushaicodemother.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCodeGeneratorServicePromptTemplateTest {

    @Test
    void shouldTreatVueMustacheInUserMessageAsLiteralText() {
        CapturingChatModel chatModel = new CapturingChatModel();
        AiCodeGeneratorService service = AiServices.builder(AiCodeGeneratorService.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .build();

        String userMessage = """
                请基于当前项目继续修改。
                当前文件: src/pages/LandingPage.vue
                ```vue
                <template>
                  <h1>{{ brand.headline }}</h1>
                </template>
                ```
                """;

        assertDoesNotThrow(() -> service.generateHtmlCode(userMessage));
        assertTrue(chatModel.lastRequestText().contains("{{ brand.headline }}"));
    }

    @Test
    void shouldRenderSlotFillSystemPromptWithoutVueExampleVariables() {
        CapturingChatModel chatModel = new CapturingChatModel("""
                {"summary":"ok","slots":[{"slotId":"home_content","content":"export const ok = true","reason":"test"}],"requiresBuild":false}
                """);
        AiSlotFillService service = AiServices.builder(AiSlotFillService.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .build();

        assertDoesNotThrow(() -> service.fillSlots("做一个电商首页", "[]", ""));
        assertTrue(chatModel.lastRequestText().contains("v-text=\\\"subtitle\\\""));
    }

    private static final class CapturingChatModel implements ChatModel {

        private final String response;
        private String lastRequestText = "";

        private CapturingChatModel() {
            this("""
                    {"htmlCode":"<html><head></head><body></body></html>","description":"ok"}
                    """);
        }

        private CapturingChatModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            this.lastRequestText = chatRequest.messages().toString();
            return ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from(response))
                    .metadata(ChatResponseMetadata.builder().finishReason(FinishReason.STOP).build())
                    .build();
        }

        String lastRequestText() {
            return lastRequestText;
        }
    }
}
