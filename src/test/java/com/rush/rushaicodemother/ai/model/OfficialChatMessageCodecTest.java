package com.rush.rushaicodemother.ai.model;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.JacksonChatMessageJsonCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class OfficialChatMessageCodecTest {

    @Test
    void roundTripPreservesThinkingAndToolRequests() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("writeFile")
                .arguments("{\"path\":\"index.html\"}")
                .build();
        AiMessage original = AiMessage.builder()
                .text("I will update the project.")
                .thinking("Inspect the existing files first.")
                .toolExecutionRequests(List.of(request))
                .build();
        JacksonChatMessageJsonCodec codec = new JacksonChatMessageJsonCodec();

        ChatMessage restoredMessage = codec.messageFromJson(codec.messageToJson(original));

        AiMessage restored = assertInstanceOf(AiMessage.class, restoredMessage);
        assertEquals(original.text(), restored.text());
        assertEquals(original.thinking(), restored.thinking());
        assertEquals(original.toolExecutionRequests(), restored.toolExecutionRequests());
    }
}
