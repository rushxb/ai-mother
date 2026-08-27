package com.rush.rushaicodemother.orchestration.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.config.ChatMemoryProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletedToolCallContextCompactorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void successfulLargeBatchWriteMustBeCompactedBeforeNextModelRequest() throws Exception {
        String sourceSecret = "source-secret-do-not-repeat ".repeat(1_000);
        ToolExecutionRequest request = request(
                "writeFiles",
                "{\"files\":["
                        + "{\"relativeFilePath\":\"src/App.vue\",\"content\":\""
                        + sourceSecret + "\"},"
                        + "{\"relativeFilePath\":\"src/main.js\",\"content\":\"main\"}]}");
        ChatRequest original = ChatRequest.builder()
                .messages(List.of(
                        UserMessage.from("生成项目"),
                        AiMessage.from(request),
                        mutationResult(
                                request,
                                "批量文件写入成功",
                                List.of("src/App.vue", "src/main.js"))
                ))
                .build();

        ChatRequest compacted = compactor(1_024).compact(original);
        AiMessage compactedAssistant = (AiMessage) compacted.messages().get(1);
        String compactedArguments = compactedAssistant.toolExecutionRequests().getFirst().arguments();
        JsonNode parsed = objectMapper.readTree(compactedArguments);

        assertNotSame(original, compacted);
        assertFalse(compactedArguments.contains(sourceSecret));
        assertTrue(compactedArguments.length() * 10 < request.arguments().length());
        assertEquals(2, parsed.get("files").size());
        assertEquals("src/App.vue", parsed.get("files").get(0).get("relativeFilePath").textValue());
        assertTrue(parsed.get("files").get(0).get("content").textValue()
                .contains("目标内容已在工作区确认"));
        assertTrue(request.arguments().contains(sourceSecret));
    }

    @Test
    void unresolvedOrFailedWriteMustKeepExactArgumentsForRecovery() {
        ToolExecutionRequest unresolved = request("writeFile",
                "{\"relativeFilePath\":\"src/App.vue\",\"content\":\""
                        + "x".repeat(2_000) + "\"}");
        ChatRequest unresolvedRequest = ChatRequest.builder()
                .messages(List.of(UserMessage.from("生成"), AiMessage.from(unresolved)))
                .build();
        assertSame(unresolvedRequest, compactor(1_024).compact(unresolvedRequest));

        ToolExecutionRequest failed = request("modifyFile",
                "{\"relativeFilePath\":\"src/App.vue\",\"oldContent\":\""
                        + "old".repeat(1_000) + "\",\"newContent\":\"new\"}");
        ChatRequest failedRequest = ChatRequest.builder()
                .messages(List.of(
                        UserMessage.from("修改"),
                        AiMessage.from(failed),
                        ToolExecutionResultMessage.builder()
                                .id("request-1")
                                .toolName("modifyFile")
                                .text("未找到替换内容")
                                .isError(true)
                                .build()
                ))
                .build();
        assertSame(failedRequest, compactor(1_024).compact(failedRequest));

        ToolExecutionRequest unknown = request("writeFile",
                "{\"relativeFilePath\":\"src/Unknown.vue\",\"content\":\""
                        + "unknown".repeat(1_000) + "\"}");
        ChatRequest unknownRequest = ChatRequest.builder()
                .messages(List.of(
                        UserMessage.from("unknown result state"),
                        AiMessage.from(unknown),
                        ToolExecutionResultMessage.from(unknown, "legacy result without status")
                ))
                .build();
        assertSame(unknownRequest, compactor(1_024).compact(unknownRequest));
    }

    @Test
    void smallOrReadToolArgumentsMustRemainUnchanged() {
        ToolExecutionRequest small = request("writeFile",
                "{\"relativeFilePath\":\"src/App.vue\",\"content\":\"small\"}");
        ToolExecutionRequest read = request("readFile",
                "{\"relativeFilePath\":\"src/App.vue\"}");
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        UserMessage.from("生成"),
                        AiMessage.from(List.of(small, read)),
                        ToolExecutionResultMessage.builder()
                                .id("request-1")
                                .toolName("writeFile")
                                .text("成功")
                                .isError(false)
                                .build(),
                        ToolExecutionResultMessage.builder()
                                .id("request-2")
                                .toolName("readFile")
                                .text("内容")
                                .isError(false)
                                .build()
                ))
                .build();

        assertSame(request, compactor(1_024).compact(request));
    }

    @Test
    void reusedRequestIdMustNotAssociateWithAnotherToolRound() {
        ToolExecutionRequest completed = request("writeFile",
                "{\"relativeFilePath\":\"src/Old.vue\",\"content\":\""
                        + "old".repeat(1_000) + "\"}");
        ToolExecutionRequest unresolved = request("writeFile",
                "{\"relativeFilePath\":\"src/New.vue\",\"content\":\""
                        + "new".repeat(1_000) + "\"}");
        ChatRequest original = ChatRequest.builder()
                .messages(List.of(
                        UserMessage.from("第一次"),
                        AiMessage.from(completed),
                        mutationResult(completed, "成功", List.of("src/Old.vue")),
                        UserMessage.from("第二次"),
                        AiMessage.from(unresolved)
                ))
                .build();

        ChatRequest compacted = compactor(1_024).compact(original);
        AiMessage unresolvedAssistant = (AiMessage) compacted.messages().get(4);

        assertEquals(unresolved.arguments(),
                unresolvedAssistant.toolExecutionRequests().getFirst().arguments());
    }

    @Test
    void unrelatedMutationEvidenceMustNotAuthorizeSourceCompaction() {
        String source = "request-source-must-remain ".repeat(1_000);
        ToolExecutionRequest request = request("writeFile",
                "{\"relativeFilePath\":\"src/App.vue\",\"content\":\""
                        + source + "\"}");
        ChatRequest original = ChatRequest.builder()
                .messages(List.of(
                        UserMessage.from("生成"),
                        AiMessage.from(request),
                        mutationResult(request, "成功", List.of("src/foreign.vue"))
                ))
                .build();

        ChatRequest compacted = compactor(1_024).compact(original);

        assertSame(original, compacted);
        AiMessage assistant = (AiMessage) compacted.messages().get(1);
        assertTrue(assistant.toolExecutionRequests().getFirst().arguments().contains(source));
    }

    @Test
    void mismatchedOrMalformedResultToolNameMustNotAuthorizeSourceCompaction() {
        String source = "request-source-must-remain ".repeat(1_000);
        ToolExecutionRequest request = request("writeFile",
                "{\"relativeFilePath\":\"src/App.vue\",\"content\":\""
                        + source + "\"}");

        for (String reportedToolName : List.of("modifyFile", " writeFile ")) {
            ChatRequest original = ChatRequest.builder()
                    .messages(List.of(
                            UserMessage.from("生成"),
                            AiMessage.from(request),
                            mutationResult(
                                    request,
                                    reportedToolName,
                                    "成功",
                                    List.of("src/App.vue"))
                    ))
                    .build();

            assertSame(original, compactor(1_024).compact(original));
        }
    }

    @Test
    void explicitNoOpEvidenceMustAuthorizeSourceCompaction() {
        String source = "confirmed-source-no-longer-needed ".repeat(1_000);
        ToolExecutionRequest request = request("writeFile",
                "{\"relativeFilePath\":\"src/App.vue\",\"content\":\""
                        + source + "\"}");
        ChatRequest original = ChatRequest.builder()
                .messages(List.of(
                        UserMessage.from("生成"),
                        AiMessage.from(request),
                        mutationResult(request, "目标状态已满足", List.of())
                ))
                .build();

        ChatRequest compacted = compactor(1_024).compact(original);
        AiMessage assistant = (AiMessage) compacted.messages().get(1);
        String compactedArguments = assistant.toolExecutionRequests().getFirst().arguments();

        assertNotSame(original, compacted);
        assertFalse(compactedArguments.contains(source));
        assertTrue(compactedArguments.contains("目标内容已在工作区确认"));
    }

    private CompletedToolCallContextCompactor compactor(int maximumArgumentsChars) {
        ChatMemoryProperties properties = new ChatMemoryProperties();
        properties.setCompletedToolArgumentsMaxChars(maximumArgumentsChars);
        return new CompletedToolCallContextCompactor(objectMapper, properties);
    }

    private ToolExecutionRequest request(String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id(name.equals("readFile") ? "request-2" : "request-1")
                .name(name)
                .arguments(arguments)
                .build();
    }

    private ToolExecutionResultMessage mutationResult(
            ToolExecutionRequest request,
            String resultText,
            List<String> effectivePaths
    ) {
        return mutationResult(request, request.name(), resultText, effectivePaths);
    }

    private ToolExecutionResultMessage mutationResult(
            ToolExecutionRequest request,
            String reportedToolName,
            String resultText,
            List<String> effectivePaths
    ) {
        TextContent content = ToolResultEvidence.effectiveMutations(
                resultText, effectivePaths);
        ToolExecutionResult result = ToolExecutionResult.builder()
                .result(content)
                .resultContents(List.of(content))
                .isError(false)
                .build();
        ToolExecutionResultMessage evidenced = ToolResultEvidence.toMessage(request, result);
        return ToolExecutionResultMessage.builder()
                .id(evidenced.id())
                .toolName(reportedToolName)
                .text(evidenced.text())
                .isError(evidenced.isError())
                .attributes(evidenced.attributes())
                .build();
    }
}
