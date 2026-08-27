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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultEvidenceTest {

    @Test
    void nullMutationPathsMustNotBeUpgradedToConfirmedNoOp() {
        assertThrows(NullPointerException.class,
                () -> ToolResultEvidence.effectiveMutations("目标状态已满足", null));
    }

    @Test
    void runtimeMessageTransportMustPreserveReadEvidenceWithStandardContentType() {
        ToolExecutionRequest request = request("readMultipleFiles");
        TextContent evidencedContent = ToolResultEvidence.successfulReads(
                "[文件] src/App.vue\n```vue\n<template />\n```",
                List.of("src/App.vue")
        );

        ToolExecutionResultMessage message = ToolResultEvidence.toMessage(
                request,
                executionResult(evidencedContent)
        );

        assertStandardTextContent(evidencedContent, message);
        assertEquals(List.of("src/App.vue"), ToolResultEvidence.successfulReadPaths(message));

        JacksonChatMessageJsonCodec codec = new JacksonChatMessageJsonCodec();
        ChatMessage restored = codec.messageFromJson(codec.messageToJson(message));
        ToolExecutionResultMessage restoredResult =
                assertInstanceOf(ToolExecutionResultMessage.class, restored);

        assertStandardTextContent(evidencedContent, restoredResult);
        assertEquals(List.of("src/App.vue"),
                ToolResultEvidence.successfulReadPaths(restoredResult));
    }

    @Test
    void runtimeMessageTransportMustPreserveEmptyAndEffectiveMutationEvidence() {
        ToolExecutionRequest request = request("writeFile");
        TextContent effective = ToolResultEvidence.effectiveMutations(
                "文件写入成功",
                List.of("src\\App.vue", "src/App.vue")
        );
        TextContent noOp = ToolResultEvidence.effectiveMutations(
                "内容已是目标状态",
                List.of()
        );

        ToolExecutionResultMessage effectiveMessage = ToolResultEvidence.toMessage(
                request, executionResult(effective));
        ToolExecutionResultMessage noOpMessage = ToolResultEvidence.toMessage(
                request, executionResult(noOp));

        assertEquals(List.of("src/App.vue"),
                ToolResultEvidence.effectiveMutationPaths(effectiveMessage));
        assertTrue(ToolResultEvidence.effectiveMutationPaths(noOpMessage).isEmpty());
        assertTrue(ToolResultEvidence.confirmsNoMutation(noOpMessage));
        assertEquals("内容已是目标状态", noOpMessage.text());
    }

    @Test
    void requestIntersectionMustCanonicalizePortablePathsAndRejectUnsafeInput() {
        assertEquals(List.of("src/App.vue", "src/foo..bar.ts"),
                ToolResultEvidence.retainRequestedPaths(
                        List.of(" src\\App.vue ", "src/./foo..bar.ts"),
                        List.of("src/App.vue", "src/foo..bar.ts", "src/foreign.ts")
                ));

        List<String> unsafePaths = List.of(
                "../outside.ts",
                "a/../b.ts",
                "/absolute.ts",
                "C:\\absolute.ts",
                "C:drive-relative.ts",
                "\\\\server\\share\\file.ts",
                ".",
                "\u0000"
        );
        for (String unsafePath : unsafePaths) {
            assertTrue(ToolResultEvidence.retainRequestedPaths(
                            List.of(unsafePath), List.of(unsafePath)).isEmpty(),
                    "非法路径必须失败关闭: " + unsafePath.replace("\u0000", "NUL"));
            assertThrows(IllegalArgumentException.class,
                    () -> ToolResultEvidence.effectiveMutations("结果", List.of(unsafePath)));
        }
    }

    @Test
    void ordinaryToolResultsMustKeepTheirOriginalTransportContract() {
        ToolExecutionRequest request = request("lintProject");
        TextContent content = TextContent.from("lint failed");
        ToolExecutionResult executionResult = ToolExecutionResult.builder()
                .result("lint failed")
                .resultContents(List.of(content))
                .isError(true)
                .attributes(Map.of("trace", "kept"))
                .build();

        ToolExecutionResultMessage message = ToolResultEvidence.toMessage(request, executionResult);

        assertEquals(request.id(), message.id());
        assertEquals(request.name(), message.toolName());
        assertEquals(List.of(content), message.contents());
        assertTrue(message.isError());
        assertEquals("kept", message.attributes().get("trace"));
        assertTrue(ToolResultEvidence.successfulReadPaths(message).isEmpty());
        assertTrue(ToolResultEvidence.effectiveMutationPaths(message).isEmpty());
    }

    @Test
    void successfulOrdinaryAttributesMustNotForgeReservedEvidence() {
        ToolExecutionRequest request = request("customTool");
        TextContent content = TextContent.from("ordinary success");
        ToolExecutionResult executionResult = ToolExecutionResult.builder()
                .result("ordinary success")
                .resultContents(List.of(content))
                .attributes(Map.of(
                        "trace", "kept",
                        "rush.tool.read.successfulPaths.v1", List.of("src/forged-read.ts"),
                        "rush.tool.mutation.effectivePaths.v1", List.of("src/forged-write.ts")
                ))
                .build();

        ToolExecutionResultMessage message = ToolResultEvidence.toMessage(request, executionResult);

        assertEquals("kept", message.attributes().get("trace"));
        assertTrue(ToolResultEvidence.successfulReadPaths(message).isEmpty());
        assertTrue(ToolResultEvidence.effectiveMutationPaths(message).isEmpty(),
                "保留普通 attributes 时不得接受伪造的项目保留证据键");
    }

    private ToolExecutionRequest request(String toolName) {
        return ToolExecutionRequest.builder()
                .id("transport-1")
                .name(toolName)
                .arguments("{}")
                .build();
    }

    private ToolExecutionResult executionResult(TextContent content) {
        return ToolExecutionResult.builder()
                .result(content)
                .resultContents(List.of(content))
                .build();
    }

    private void assertStandardTextContent(
            TextContent expected,
            ToolExecutionResultMessage actual
    ) {
        assertEquals(TextContent.class, actual.contents().getFirst().getClass(),
                "私有证据子类型不能进入 Redis/模型消息序列化边界");
        assertEquals(expected.text(), actual.text());
    }
}
