package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceSelector;
import com.rush.rushaicodemother.core.AiCodeGeneratorFacade;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.core.handler.StreamHandlerExecutor;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.GenerationMemoryContextService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeavyGenerationExecutionServiceTest {

    @Test
    void autoRepairPromptMustNotForwardRawFailureDetailsToMemoryOrModel() {
        GenerationMemoryContextService memoryContextService = mock(GenerationMemoryContextService.class);
        HeavyGenerationFailureRecoveryService failureRecoveryService = mock(HeavyGenerationFailureRecoveryService.class);
        HeavyGenerationExecutionService service = new HeavyGenerationExecutionService(
                mock(AiCodeGeneratorFacade.class),
                mock(ChatHistoryService.class),
                mock(GenerationAppStateService.class),
                memoryContextService,
                mock(GenerationOrchestrationMetricsCollector.class),
                mock(GenerationPerformanceSelector.class),
                failureRecoveryService,
                mock(HeavyGenerationSessionCompletionService.class),
                mock(StreamHandlerExecutor.class)
        );
        GenerationPreparation preparation = new GenerationPreparation(
                CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeEnum.VUE_PROJECT,
                false,
                "create",
                "build a page",
                List.of(),
                Map.of(),
                null,
                Map.of(),
                "task-43"
        );
        RuntimeException failure = new RuntimeException("provider-api-key=secret-value");
        GenerationErrorClassifier.GenerationError classifiedFailure = GenerationErrorClassifier.classify(failure);
        when(failureRecoveryService.classifyGenerationError(failure)).thenReturn(classifiedFailure);
        when(memoryContextService.buildAutoRepairMemoryContext(
                43L,
                "task-43",
                classifiedFailure.message(),
                1
        )).thenReturn("");

        String prompt = service.buildAutoRepairPrompt(43L, preparation, failure, 1);

        assertTrue(prompt.contains(classifiedFailure.message()));
        assertFalse(prompt.contains("secret-value"));
        verify(memoryContextService).buildAutoRepairMemoryContext(
                43L,
                "task-43",
                classifiedFailure.message(),
                1
        );
    }

    @Test
    void exhaustedGenerationFailureMustHideInternalDetailsAndPreserveCause() {
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        GenerationAppStateService appStateService = mock(GenerationAppStateService.class);
        GenerationMemoryContextService memoryContextService = mock(GenerationMemoryContextService.class);
        GenerationOrchestrationMetricsCollector metricsCollector = mock(GenerationOrchestrationMetricsCollector.class);
        GenerationPerformanceSelector performanceSelector = mock(GenerationPerformanceSelector.class);
        HeavyGenerationFailureRecoveryService failureRecoveryService = mock(HeavyGenerationFailureRecoveryService.class);
        HeavyGenerationSessionCompletionService completionService = mock(HeavyGenerationSessionCompletionService.class);
        StreamHandlerExecutor streamHandlerExecutor = mock(StreamHandlerExecutor.class);
        HeavyGenerationExecutionService service = spy(new HeavyGenerationExecutionService(
                facade,
                chatHistoryService,
                appStateService,
                memoryContextService,
                metricsCollector,
                performanceSelector,
                failureRecoveryService,
                completionService,
                streamHandlerExecutor
        ));
        GenerationPreparation preparation = new GenerationPreparation(
                CodeGenTypeEnum.HTML,
                CodeGenTypeEnum.HTML,
                false,
                "create",
                "build a page",
                List.of(),
                Map.of(),
                null,
                Map.of(),
                "task-42"
        );
        GenerationSession session = new GenerationSession(preparation);
        User user = User.builder().id(7L).build();
        GenerationPerformanceProfile profile = GenerationPerformanceProfile.speedFirst();
        RuntimeException failure = new RuntimeException("provider-api-key=secret-value");
        when(performanceSelector.select(anyBoolean(), anyBoolean(), eq(CodeGenTypeEnum.HTML)))
                .thenReturn(profile);
        when(failureRecoveryService.classifyGenerationError(failure)).thenReturn(
                new GenerationErrorClassifier.GenerationError(
                        GenerationErrorClassifier.CATEGORY_RUNTIME,
                        failure.getMessage(),
                        false
                )
        );
        doThrow(failure).when(service).executeGenerationRound(
                eq(42L),
                same(user),
                eq(CodeGenTypeEnum.HTML),
                eq("build a page"),
                same(session),
                any(StringBuilder.class),
                any(long[].class),
                same(profile)
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.runGenerationWithAutoRepair(42L, user, preparation, session)
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        assertEquals("代码生成失败，请稍后重试", exception.getMessage());
        assertFalse(exception.getMessage().contains("secret-value"));
        assertSame(failure, exception.getCause());
    }
}
