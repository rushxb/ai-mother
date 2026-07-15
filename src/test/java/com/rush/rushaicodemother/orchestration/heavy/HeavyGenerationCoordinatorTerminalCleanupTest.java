package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTerminalOutcome;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class HeavyGenerationCoordinatorTerminalCleanupTest {

    @Mock
    private GenerationEventPublisher eventPublisher;
    @Mock
    private GenerationSessionRegistry sessionRegistry;
    @Mock
    private GenerationPerformanceMonitorService performanceMonitorService;
    @Mock
    private HeavyGenerationBuildValidationService buildValidationService;
    @Mock
    private HeavyGenerationExecutionService executionService;
    @Mock
    private HeavyGenerationFailureRecoveryService failureRecoveryService;
    @Mock
    private HeavyGenerationFinalizationService finalizationService;
    @Mock
    private HeavyGenerationPreparationService preparationService;
    @Mock
    private HeavyGenerationSessionCompletionService completionService;
    @Mock
    private GenerationTaskLifecycleService lifecycleService;
    @Mock
    private GenerationToolExecutionContextService toolExecutionContextService;
    @Mock
    private GenerationTraceService traceService;
    @Mock
    private GenerationExecutionContextService executionContextService;

    private HeavyGenerationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new HeavyGenerationCoordinator(
                eventPublisher,
                sessionRegistry,
                performanceMonitorService,
                buildValidationService,
                executionService,
                failureRecoveryService,
                finalizationService,
                preparationService,
                completionService,
                lifecycleService,
                toolExecutionContextService,
                traceService,
                executionContextService
        );
    }

    @Test
    void successfulLifecyclePersistenceMustNotReleaseApplicationStateTwice() {
        TerminalFixture fixture = fixture();

        complete(fixture, GenerationTerminalOutcome.SUCCESS, null);

        verify(completionService).completeClaimed(11L, fixture.session(), fixture.preparation(),
                GenerationTerminalOutcome.SUCCESS);
        verify(lifecycleService, never()).releaseGenerationState(anyString(), anyLong());
        verifyCommonCleanup(fixture, GenerationTerminalOutcome.SUCCESS);
    }

    @Test
    void lifecyclePersistenceFailureMustUseOwnedStateReleaseAsFallbackAndContinueCleanup() {
        TerminalFixture fixture = fixture();
        doThrow(new IllegalStateException("persistence unavailable"))
                .when(completionService)
                .completeClaimed(11L, fixture.session(), fixture.preparation(), GenerationTerminalOutcome.FAILED);

        complete(fixture, GenerationTerminalOutcome.FAILED, new IllegalStateException("generation failed"));

        verify(lifecycleService).releaseGenerationState("task-11", 11L);
        verifyCommonCleanup(fixture, GenerationTerminalOutcome.FAILED);
    }

    @Test
    void cancellationClaimedWithoutRequestArgumentMustPublishCanonicalCancelledEvent() {
        TerminalFixture fixture = fixture();
        fixture.session().bindTaskRequest(fixture.request());

        complete(fixture, null, GenerationTerminalOutcome.CANCELLED, null);

        verify(eventPublisher).publish(
                same(fixture.request()),
                eq(GenerationEventType.TASK_CANCELLED),
                eq(GenerationTerminalOutcome.CANCELLED.eventMessage()),
                anyMap()
        );
        verifyCommonCleanup(fixture, GenerationTerminalOutcome.CANCELLED);
    }

    @Test
    void competingTerminalCallersMustPersistAndPublishOnlyOnce() {
        TerminalFixture fixture = fixture();

        complete(fixture, GenerationTerminalOutcome.SUCCESS, null);
        complete(fixture, GenerationTerminalOutcome.FAILED, new IllegalStateException("late failure"));

        verify(completionService, times(1)).completeClaimed(
                11L, fixture.session(), fixture.preparation(), GenerationTerminalOutcome.SUCCESS);
        verify(eventPublisher, times(1)).publish(
                same(fixture.request()),
                eq(GenerationEventType.TASK_DONE),
                eq(GenerationTerminalOutcome.SUCCESS.eventMessage()),
                anyMap()
        );
    }

    @Test
    void terminalStreamFailureMustNotPreventLifecyclePersistence() {
        TerminalFixture fixture = fixture();
        RuntimeException generationFailure = new RuntimeException("generation failed");
        doThrow(new IllegalStateException("stream unavailable"))
                .when(failureRecoveryService)
                .emitGenerationError(11L, fixture.preparation(), fixture.session(), generationFailure);

        complete(fixture, GenerationTerminalOutcome.FAILED, generationFailure);

        verify(completionService).completeClaimed(11L, fixture.session(), fixture.preparation(),
                GenerationTerminalOutcome.FAILED);
        verify(lifecycleService, never()).releaseGenerationState(anyString(), anyLong());
        verifyCommonCleanup(fixture, GenerationTerminalOutcome.FAILED);
    }

    private void verifyCommonCleanup(TerminalFixture fixture, GenerationTerminalOutcome outcome) {
        verify(performanceMonitorService).finishTask("task-11", outcome.status());
        verify(sessionRegistry).remove(11L, fixture.session());
        verify(toolExecutionContextService).clearContext(11L);
        verify(executionContextService).finish("task-11", outcome.status());
    }

    private void complete(TerminalFixture fixture,
                          GenerationTerminalOutcome outcome,
                          Throwable failure) {
        complete(fixture, fixture.request(), outcome, failure);
    }

    private void complete(TerminalFixture fixture,
                          GenerationTaskRequest request,
                          GenerationTerminalOutcome outcome,
                          Throwable failure) {
        ReflectionTestUtils.invokeMethod(
                coordinator,
                "completeHeavyTask",
                11L,
                request,
                fixture.preparation(),
                fixture.session(),
                outcome,
                failure
        );
    }

    private TerminalFixture fixture() {
        GenerationPreparation preparation = new GenerationPreparation(
                CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeEnum.VUE_PROJECT,
                false,
                "build",
                "prompt",
                List.of(),
                Map.of(),
                null,
                Map.of(),
                "task-11"
        );
        GenerationSession session = new GenerationSession(preparation);
        App app = new App();
        app.setId(11L);
        User user = new User();
        user.setId(22L);
        return new TerminalFixture(preparation, session, new GenerationTaskRequest(app, "prompt", user));
    }

    private record TerminalFixture(GenerationPreparation preparation,
                                   GenerationSession session,
                                   GenerationTaskRequest request) {
    }
}
