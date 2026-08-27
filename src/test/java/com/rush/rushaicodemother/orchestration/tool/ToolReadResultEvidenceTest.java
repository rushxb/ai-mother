package com.rush.rushaicodemother.orchestration.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.JacksonChatMessageJsonCodec;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolReadResultEvidenceTest {

    @Test
    void runtimeMessageTransportMustPreserveEvidenceWithStandardContentType() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("batch-transport-1")
                .name("readMultipleFiles")
                .arguments("{\"relativeFilePaths\":[\"src/App.vue\"]}")
                .build();
        TextContent evidencedContent = ToolReadResultEvidence.successfulReads(
                "[文件] src/App.vue\n```vue\n<template />\n```",
                List.of("src/App.vue")
        );
        ToolExecutionResult executionResult = ToolExecutionResult.builder()
                .result(evidencedContent)
                .resultContents(List.of(evidencedContent))
                .build();

        ToolExecutionResultMessage message = ToolReadResultEvidence.toMessage(
                request,
                executionResult
        );

        assertEquals(TextContent.class, message.contents().getFirst().getClass(),
                "私有证据子类型不能进入 Redis/模型消息序列化边界");
        assertEquals(evidencedContent.text(), message.text());
        assertEquals(List.of("src/App.vue"), ToolReadResultEvidence.successfulPaths(message));

        JacksonChatMessageJsonCodec codec = new JacksonChatMessageJsonCodec();
        ChatMessage restored = codec.messageFromJson(codec.messageToJson(message));
        ToolExecutionResultMessage restoredResult =
                assertInstanceOf(ToolExecutionResultMessage.class, restored);

        assertEquals(TextContent.class, restoredResult.contents().getFirst().getClass());
        assertEquals(evidencedContent.text(), restoredResult.text());
        assertEquals(List.of("src/App.vue"),
                ToolReadResultEvidence.successfulPaths(restoredResult));
    }

    @Test
    void ordinaryToolResultsMustKeepTheirOriginalTransportContract() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("ordinary-1")
                .name("lintProject")
                .arguments("{}")
                .build();
        TextContent content = TextContent.from("lint failed");
        ToolExecutionResult executionResult = ToolExecutionResult.builder()
                .result("lint failed")
                .resultContents(List.of(content))
                .isError(true)
                .attributes(Map.of("trace", "kept"))
                .build();

        ToolExecutionResultMessage message = ToolReadResultEvidence.toMessage(
                request,
                executionResult
        );

        assertEquals(request.id(), message.id());
        assertEquals(request.name(), message.toolName());
        assertEquals(List.of(content), message.contents());
        assertTrue(message.isError());
        assertEquals("kept", message.attributes().get("trace"));
        assertTrue(ToolReadResultEvidence.successfulPaths(message).isEmpty());
    }
}
