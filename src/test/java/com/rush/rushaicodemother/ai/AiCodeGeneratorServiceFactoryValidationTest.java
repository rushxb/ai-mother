package com.rush.rushaicodemother.ai;

import com.rush.rushaicodemother.ai.generation.HtmlLightweightCodeGenerationAdapter;
import com.rush.rushaicodemother.ai.generation.LightweightCodeGenerationAdapter;
import com.rush.rushaicodemother.ai.generation.LightweightCodeGenerationExecutor;
import com.rush.rushaicodemother.ai.generation.MultiFileLightweightCodeGenerationAdapter;
import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.ai.prompt.PromptSystemMessageTransformer;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelInvocationCancellationBridge;
import com.rush.rushaicodemother.service.ChatHistoryService;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AiCodeGeneratorServiceFactoryValidationTest {

    private final ChatMemoryStore chatMemoryStore = mock(ChatMemoryStore.class);
    private final ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
    private final StreamingModelFactory streamingModelFactory = mock(StreamingModelFactory.class);
    private final PromptSystemMessageTransformer promptSystemMessageTransformer =
            mock(PromptSystemMessageTransformer.class);
    private final AiCodeGeneratorServiceFactory serviceFactory = new AiCodeGeneratorServiceFactory(
            chatMemoryStore,
            chatHistoryService,
            streamingModelFactory,
            promptSystemMessageTransformer,
            new GenerationModelInvocationCancellationBridge(),
            lightweightExecutor(List.of(
                    new HtmlLightweightCodeGenerationAdapter(),
                    new MultiFileLightweightCodeGenerationAdapter()
            ))
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
    void shouldRejectProjectGenerationBeforeCreatingService() {
        for (CodeGenTypeEnum codeGenType : new CodeGenTypeEnum[]{
                CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeEnum.BACKEND_PROJECT,
                CodeGenTypeEnum.FULL_STACK_PROJECT
        }) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> serviceFactory.getAiCodeGeneratorService(1L, codeGenType));

            assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
            assertEquals("当前生成类型未注册轻量代码生成协议", exception.getMessage());
        }
        verifyNoFactoryDependencyInteractions();
    }

    @Test
    void shouldRejectATypeWhenItsLightweightProtocolAdapterIsNotRegistered() {
        AiCodeGeneratorServiceFactory htmlOnlyFactory = new AiCodeGeneratorServiceFactory(
                chatMemoryStore,
                chatHistoryService,
                streamingModelFactory,
                promptSystemMessageTransformer,
                new GenerationModelInvocationCancellationBridge(),
                lightweightExecutor(List.of(new HtmlLightweightCodeGenerationAdapter()))
        );

        BusinessException exception = assertThrows(BusinessException.class,
                () -> htmlOnlyFactory.getAiCodeGeneratorService(
                        1L, CodeGenTypeEnum.MULTI_FILE));

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        assertEquals("当前生成类型未注册轻量代码生成协议", exception.getMessage());
        verifyNoFactoryDependencyInteractions();
    }

    private void verifyNoFactoryDependencyInteractions() {
        verifyNoInteractions(
                chatMemoryStore, chatHistoryService,
                streamingModelFactory,
                promptSystemMessageTransformer);
    }

    private LightweightCodeGenerationExecutor lightweightExecutor(
            List<LightweightCodeGenerationAdapter<?>> adapters) {
        return new LightweightCodeGenerationExecutor(adapters);
    }
}
