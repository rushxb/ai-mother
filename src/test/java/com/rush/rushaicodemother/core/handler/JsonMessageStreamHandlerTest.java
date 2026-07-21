package com.rush.rushaicodemother.core.handler;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import com.rush.rushaicodemother.ai.tools.ToolManager;
import com.rush.rushaicodemother.ai.tools.BaseTool;
import com.rush.rushaicodemother.ai.tools.ToolRiskLevel;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.service.ChatHistoryService;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JsonMessageStreamHandlerTest {

    @Test
    void handleToolCallShouldAccumulatePartialArgumentsForFilePreview() {
        JsonMessageStreamHandler handler = new JsonMessageStreamHandler(mock(ToolManager.class));
        Flux<GenerationStreamEvent> originFlux = Flux.just(
                toolCall("req-1", "{\"relativeFilePath\":\"src/App.vue\",\"content\":\"hel"),
                toolCall("req-1", "lo"),
                toolCall("req-1", "\\nworld\"}")
        );
        User loginUser = new User();
        loginUser.setId(1L);

        List<GenerationStreamEvent> events = handler.handle(originFlux, mock(ChatHistoryService.class), 1L, loginUser)
                .collectList()
                .block();

        assertEquals(3, events.size());
        assertEquals("hel", events.get(0).getData().get("content"));
        assertEquals("hello", events.get(1).getData().get("content"));
        assertEquals("hello\nworld", events.get(2).getData().get("content"));
        assertEquals("src/App.vue", events.get(0).getData().get("filePath"));
        assertFalse(events.get(2).getData().containsKey("arguments"));
        assertFalse(events.get(2).getData().containsKey("result"));
    }

    @Test
    void handleToolCallShouldNotExposeIncompleteFilePath() {
        JsonMessageStreamHandler handler = new JsonMessageStreamHandler(mock(ToolManager.class));
        Flux<GenerationStreamEvent> originFlux = Flux.just(
                toolCall("req-2", "{\"relativeFilePath\":\"package"),
                toolCall("req-2", ".json\",\"content\":\"{\\n")
        );
        User loginUser = new User();
        loginUser.setId(1L);

        List<GenerationStreamEvent> events = handler.handle(originFlux, mock(ChatHistoryService.class), 1L, loginUser)
                .collectList()
                .block();

        assertEquals(2, events.size());
        assertFalse(events.get(0).getData().containsKey("filePath"));
        assertEquals("package.json", events.get(1).getData().get("filePath"));
        assertEquals("{\n", events.get(1).getData().get("content"));
    }

    @Test
    void handleToolCallShouldSeparateFilesWithSameToolIndexByRequestId() {
        JsonMessageStreamHandler handler = new JsonMessageStreamHandler(mock(ToolManager.class));
        Flux<GenerationStreamEvent> originFlux = Flux.just(
                toolCall("req-package", "{\"relativeFilePath\":\"package.json\",\"content\":\"{}\"}"),
                toolCall("req-i18n", "{\"relativeFilePath\":\"src/utils/i18n.js\",\"content\":\"export"),
                toolCall("req-i18n", " const locale = 'zh-CN'\"}")
        );
        User loginUser = new User();
        loginUser.setId(1L);

        List<GenerationStreamEvent> events = handler.handle(originFlux, mock(ChatHistoryService.class), 1L, loginUser)
                .collectList()
                .block();

        assertEquals(3, events.size());
        assertEquals("package.json", events.get(0).getData().get("filePath"));
        assertEquals("{}", events.get(0).getData().get("content"));
        assertEquals("src/utils/i18n.js", events.get(1).getData().get("filePath"));
        assertEquals("export", events.get(1).getData().get("content"));
        assertEquals("src/utils/i18n.js", events.get(2).getData().get("filePath"));
        assertEquals("export const locale = 'zh-CN'", events.get(2).getData().get("content"));
    }

    @Test
    void handleGenerationStageShouldNotPersistAsBuildResult() {
        JsonMessageStreamHandler handler = new JsonMessageStreamHandler(mock(ToolManager.class));
        Flux<GenerationStreamEvent> originFlux = Flux.just(GenerationStreamEvent.generationStage("代码生成完成", Map.of(
                "stage", "codegen_done",
                "status", "transition",
                "summary", "代码已生成，后台正在执行构建校验"
        )));
        User loginUser = new User();
        loginUser.setId(1L);

        List<GenerationStreamEvent> events = handler.handle(originFlux, mock(ChatHistoryService.class), 1L, loginUser)
                .collectList()
                .block();

        assertEquals(1, events.size());
        assertEquals(GenerationStreamEvent.GENERATION_STAGE, events.get(0).getType());
        assertEquals("codegen_done", events.get(0).getData().get("stage"));
    }

    @Test
    void privateThinkingMustBeDroppedBeforeClientAndHistoryProcessing() {
        JsonMessageStreamHandler handler = new JsonMessageStreamHandler(mock(ToolManager.class));
        User loginUser = new User();
        loginUser.setId(1L);

        List<GenerationStreamEvent> events = handler.handle(
                        Flux.just(
                                GenerationStreamEvent.aiThinkingDelta("private chain of thought"),
                                GenerationStreamEvent.aiDelta("visible answer")
                        ),
                        mock(ChatHistoryService.class),
                        1L,
                        loginUser
                )
                .collectList()
                .block();

        assertEquals(1, events.size());
        assertEquals(GenerationStreamEvent.AI_DELTA, events.getFirst().getType());
        assertEquals("visible answer", events.getFirst().getText());
    }

    @Test
    void toolEventsMustExposeStableRiskMetadata() {
        ToolManager toolManager = mock(ToolManager.class);
        BaseTool tool = mock(BaseTool.class);
        when(toolManager.getTool("writeFile")).thenReturn(tool);
        when(tool.getRiskLevel()).thenReturn(ToolRiskLevel.WRITE);
        when(tool.generateToolRequestResponse()).thenReturn("write");
        JsonMessageStreamHandler handler = new JsonMessageStreamHandler(toolManager);
        User loginUser = new User();
        loginUser.setId(1L);

        GenerationStreamEvent event = handler.handle(
                        Flux.just(toolCall("risk-1", "{\"relativeFilePath\":\"src/App.vue\"}")),
                        mock(ChatHistoryService.class),
                        1L,
                        loginUser
                )
                .blockFirst();

        assertEquals("write", event.getData().get("riskLevel"));
    }

    @Test
    void toolResultMustNotPersistRawArgumentsResultsOrSecretsInHistory() {
        String secret = "history-secret-do-not-store";
        ToolManager toolManager = mock(ToolManager.class);
        BaseTool tool = mock(BaseTool.class);
        when(toolManager.getTool("writeFile")).thenReturn(tool);
        when(tool.getRiskLevel()).thenReturn(ToolRiskLevel.WRITE);
        when(tool.generateToolExecutedResult(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("[工具调用] 写入文件 src/App.vue\nconst password = \"" + secret + "\";");
        JsonMessageStreamHandler handler = new JsonMessageStreamHandler(toolManager);
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        User loginUser = new User();
        loginUser.setId(1L);
        GenerationStreamEvent rawResult = GenerationStreamEvent.toolResult(
                "password=" + secret,
                Map.of(
                        "toolName", "writeFile",
                        "arguments", "{\"relativeFilePath\":\"src/App.vue\","
                                + "\"content\":\"const password = " + secret + "\"}",
                        "result", "password=" + secret,
                        "requestId", "result-1"
                )
        );

        List<GenerationStreamEvent> publicEvents = handler.handle(
                        Flux.just(rawResult), chatHistoryService, 1L, loginUser)
                .collectList()
                .block();
        GenerationStreamEvent publicResult = publicEvents.getFirst();

        assertFalse(publicResult.getData().containsKey("arguments"));
        assertFalse(publicResult.getData().containsKey("result"));
        assertFalse(String.valueOf(publicResult.getData()).contains(secret));
        assertTrue(String.valueOf(publicResult.getData().get("content")).contains("[REDACTED]"));
        ArgumentCaptor<String> history = ArgumentCaptor.forClass(String.class);
        verify(chatHistoryService).addChatMessage(
                org.mockito.ArgumentMatchers.eq(1L),
                history.capture(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(1L)
        );
        assertFalse(history.getValue().contains(secret));
    }

    private GenerationStreamEvent toolCall(String requestId, String arguments) {
        return GenerationStreamEvent.toolCall("", Map.of(
                "toolName", "writeFile",
                "arguments", arguments,
                "requestId", requestId,
                "toolIndex", 0
        ));
    }
}
