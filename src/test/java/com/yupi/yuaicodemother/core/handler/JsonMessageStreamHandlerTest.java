package com.yupi.yuaicodemother.core.handler;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import com.yupi.yuaicodemother.ai.tools.ToolManager;
import com.yupi.yuaicodemother.model.entity.User;
import com.yupi.yuaicodemother.service.ChatHistoryService;

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

    private GenerationStreamEvent toolCall(String requestId, String arguments) {
        return GenerationStreamEvent.toolCall("", Map.of(
                "toolName", "writeFile",
                "arguments", arguments,
                "requestId", requestId,
                "toolIndex", 0
        ));
    }
}
