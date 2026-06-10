package com.rush.rushaicodemother.core.handler;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import com.rush.rushaicodemother.ai.tools.ToolManager;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.service.ChatHistoryService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class JsonMessageStreamHandlerTest {

    @Test
    void handleToolCallShouldAccumulatePartialArgumentsForFilePreview() {
        JsonMessageStreamHandler handler = new JsonMessageStreamHandler();
        ReflectionTestUtils.setField(handler, "toolManager", mock(ToolManager.class));
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
    }

    @Test
    void handleToolCallShouldNotExposeIncompleteFilePath() {
        JsonMessageStreamHandler handler = new JsonMessageStreamHandler();
        ReflectionTestUtils.setField(handler, "toolManager", mock(ToolManager.class));
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
        JsonMessageStreamHandler handler = new JsonMessageStreamHandler();
        ReflectionTestUtils.setField(handler, "toolManager", mock(ToolManager.class));
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
        JsonMessageStreamHandler handler = new JsonMessageStreamHandler();
        ReflectionTestUtils.setField(handler, "toolManager", mock(ToolManager.class));
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

    private GenerationStreamEvent toolCall(String requestId, String arguments) {
        return GenerationStreamEvent.toolCall("", Map.of(
                "toolName", "writeFile",
                "arguments", arguments,
                "requestId", requestId,
                "toolIndex", 0
        ));
    }
}
