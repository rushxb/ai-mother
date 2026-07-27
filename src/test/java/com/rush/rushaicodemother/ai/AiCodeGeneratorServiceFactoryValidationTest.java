package com.rush.rushaicodemother.ai;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.ai.prompt.PromptSystemMessageTransformer;
import com.rush.rushaicodemother.ai.tools.ToolManager;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.tool.AiToolInvocationPolicy;
import com.rush.rushaicodemother.orchestration.tool.CompletedToolCallContextCompactor;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelInvocationCancellationBridge;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionFailurePolicy;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
            promptSystemMessageTransformer,
            mock(CompletedToolCallContextCompactor.class),
            new GenerationModelInvocationCancellationBridge()
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

    @Test
    void shouldBuildControllableSimpleTokenStreamContract() {
        AiCodeGeneratorService service = AiServices.builder(AiCodeGeneratorService.class)
                .chatModel(mock(ChatModel.class))
                .streamingChatModel(mock(StreamingChatModel.class))
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(1))
                .build();

        TokenStream tokenStream = service.generateHtmlCodeStream(
                "生成页面",
                InvocationParameters.from("trace-id", "test-trace")
        );

        assertNotNull(tokenStream);
    }

    @Test
    void cacheKeyMustSeparateDifferentToolRoundBudgets() {
        GenerationPerformanceProfile lowBudget = new GenerationPerformanceProfile(
                GenerationPerformanceProfile.ModelTier.BALANCED,
                false,
                10,
                "低工具预算"
        );
        GenerationPerformanceProfile highBudget = new GenerationPerformanceProfile(
                GenerationPerformanceProfile.ModelTier.BALANCED,
                false,
                24,
                "高工具预算"
        );

        String lowBudgetKey = ReflectionTestUtils.invokeMethod(
                serviceFactory, "buildCacheKey", 1L, CodeGenTypeEnum.VUE_PROJECT, lowBudget);
        String highBudgetKey = ReflectionTestUtils.invokeMethod(
                serviceFactory, "buildCacheKey", 1L, CodeGenTypeEnum.VUE_PROJECT, highBudget);

        assertNotEquals(lowBudgetKey, highBudgetKey);
    }

    private void verifyNoFactoryDependencyInteractions() {
        verifyNoInteractions(
                chatMemoryStore, chatHistoryService, toolManager,
                streamingModelFactory, toolExecutionFailurePolicy,
                aiToolInvocationPolicy,
                promptSystemMessageTransformer);
    }
}
