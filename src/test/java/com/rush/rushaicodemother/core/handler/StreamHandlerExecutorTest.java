package com.rush.rushaicodemother.core.handler;

import com.rush.rushaicodemother.ai.tools.ToolManager;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.ChatHistoryService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class StreamHandlerExecutorTest {

    @Test
    void routesCodeGenerationTypesThroughRegisteredHandlers() {
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        User loginUser = new User();
        loginUser.setId(7L);
        StreamHandlerExecutor executor = new StreamHandlerExecutor(List.of(
                new SimpleTextStreamHandler(),
                new JsonMessageStreamHandler(mock(ToolManager.class))
        ));
        GenerationStreamEvent sourceEvent = GenerationStreamEvent.toolCall(
                "",
                Map.of(
                        "requestId", "tool-1",
                        "toolName", "unregisteredTool",
                        "arguments", "{}"
                )
        );

        GenerationStreamEvent simpleResult = executor.doExecute(
                Flux.just(sourceEvent),
                chatHistoryService,
                1L,
                loginUser,
                CodeGenTypeEnum.HTML
        ).blockLast();
        GenerationStreamEvent structuredResult = executor.doExecute(
                Flux.just(sourceEvent),
                chatHistoryService,
                1L,
                loginUser,
                CodeGenTypeEnum.VUE_PROJECT
        ).blockLast();

        assertSame(sourceEvent, simpleResult);
        assertNotSame(sourceEvent, structuredResult);
        assertTrue(structuredResult.getText().contains("未注册工具"));
    }

    @Test
    void rejectsDuplicateCodeGenerationTypeRegistrations() {
        assertThrows(
                IllegalStateException.class,
                () -> new StreamHandlerExecutor(List.of(
                        new SimpleTextStreamHandler(),
                        new SimpleTextStreamHandler()
                ))
        );
    }

    @Test
    void reportsMissingHandlerAsStableBusinessFailure() {
        StreamHandlerExecutor executor = new StreamHandlerExecutor(
                List.of(new SimpleTextStreamHandler()));

        BusinessException actual = assertThrows(
                BusinessException.class,
                () -> executor.doExecute(
                        Flux.empty(),
                        mock(ChatHistoryService.class),
                        1L,
                        new User(),
                        CodeGenTypeEnum.BACKEND_PROJECT
                )
        );

        assertTrue(actual.getMessage().contains("未注册生成流处理 adapter"));
    }

    @Test
    void rejectsEmptyRegistryAndAdapterTypeDeclaration() {
        GenerationStreamHandlerAdapter undeclaredAdapter = new GenerationStreamHandlerAdapter() {
            @Override
            public Set<CodeGenTypeEnum> supportedCodeGenTypes() {
                return Set.of();
            }

            @Override
            public Flux<GenerationStreamEvent> handle(
                    Flux<GenerationStreamEvent> originFlux,
                    ChatHistoryService chatHistoryService,
                    long appId,
                    User loginUser
            ) {
                return originFlux;
            }
        };

        assertThrows(
                IllegalStateException.class,
                () -> new StreamHandlerExecutor(List.of())
        );
        assertThrows(
                IllegalStateException.class,
                () -> new StreamHandlerExecutor(List.of(undeclaredAdapter))
        );
    }
}
