package com.rush.rushaicodemother.ai;

import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.ai.prompt.PromptSystemMessageTransformer;
import com.rush.rushaicodemother.ai.tools.ToolManager;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.tool.AiToolInvocationPolicy;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionFailurePolicy;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AiCodeGeneratorServiceFactoryValidationTest {

    private final ChatMemoryStore chatMemoryStore = mock(ChatMemoryStore.class);
    private final ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
    private final ToolManager toolManager = mock(ToolManager.class);
    private final StreamingModelFactory streamingModelFactory = mock(StreamingModelFactory.class);
    private final ToolExecutionFailurePolicy toolExecutionFailurePolicy = mock(ToolExecutionFailurePolicy.class);
    private final AiToolInvocationPolicy aiToolInvocationPolicy = mock(AiToolInvocationPolicy.class);
    private final PromptSystemMessageTransformer promptSystemMessageTransformer =
            mock(PromptSystemMessageTransformer.class);
    private final AiCodeGeneratorServiceFactory serviceFactory = new AiCodeGeneratorServiceFactory(
            chatMemoryStore,
            chatHistoryService,
            toolManager,
            streamingModelFactory,
            toolExecutionFailurePolicy,
            aiToolInvocationPolicy,
            promptSystemMessageTransformer
    );

    @Test
    void shouldRejectNonPositiveAppIdBeforeCreatingService() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> serviceFactory.getAiCodeGeneratorService(0L));

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        assertEquals("应用 ID 必须大于 0", exception.getMessage());
        verifyNoFactoryDependencyInteractions();
    }

    @Test
    void shouldRejectMissingCodeGenerationTypeBeforeCreatingService() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> serviceFactory.getAiCodeGeneratorService(1L, null));

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        assertEquals("代码生成类型不能为空", exception.getMessage());
        verifyNoFactoryDependencyInteractions();
    }

    private void verifyNoFactoryDependencyInteractions() {
        verifyNoInteractions(
                chatMemoryStore, chatHistoryService, toolManager,
                streamingModelFactory, toolExecutionFailurePolicy,
                aiToolInvocationPolicy,
                promptSystemMessageTransformer);
    }
}
