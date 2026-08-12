package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTerminalOutcome;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTaskFinalizer;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommand;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.identity.GenerationTaskIdGenerator;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspaceService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceReleaseService;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private GenerationTaskFinalizer taskFinalizer;
    @Mock
    private GenerationTaskLifecycleService lifecycleService;
    @Mock
    private GenerationToolExecutionContextService toolExecutionContextService;
    @Mock
    private GenerationTraceService traceService;
    @Mock
    private GenerationExecutionContextService executionContextService;
    @Mock
    private GenerationTaskIdGenerator taskIdGenerator;
    @Mock
    private GenerationExecutionWorkspaceService executionWorkspaceService;
    @Mock
    private GenerationWorkspaceReleaseService workspaceReleaseService;
    @Mock
    private GenerationPerformanceMonitorService.SpanTimer finalizationSpan;

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
                taskFinalizer,
                lifecycleService,
                toolExecutionContextService,
                org.mockito.Mockito.mock(
                        com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService.class),
                traceService,
                executionContextService,
                taskIdGenerator,
                executionWorkspaceService,
                workspaceReleaseService
        );
    }

    @Test
    void successfulHeavyFinalizationMustReleaseWorkspaceAfterResultSummaries() {
        TerminalFixture fixture = fixture();
        when(performanceMonitorService.startSpan(
                fixture.preparation().taskId(),
                "finalization",
                GenerationSpanCategory.FINALIZATION
        )).thenReturn(finalizationSpan);

        ReflectionTestUtils.invokeMethod(
                coordinator,
                "runFinalizationSteps",
                11L,
                fixture.preparation(),
                fixture.session()
        );

        InOrder order = inOrder(finalizationService, workspaceReleaseService);
        order.verify(finalizationService).emitDiffSummaryIfAvailable(
                11L, fixture.preparation(), fixture.session());
        order.verify(finalizationService).emitCommitResultIfAvailable(
                11L, fixture.preparation(), fixture.session());
        order.verify(workspaceReleaseService).releaseVerified(
                org.mockito.ArgumentMatchers.eq(fixture.session()),
                org.mockito.ArgumentMatchers.eq(CodeGenTypeEnum.VUE_PROJECT),
                org.mockito.ArgumentMatchers.nullable(GenerationFinalizationCommand.class));
        verify(finalizationSpan).success();
    }

    @Test
    void successfulLifecyclePersistenceMustNotReleaseApplicationStateTwice() {
        TerminalFixture fixture = fixture();

        complete(fixture, GenerationTerminalOutcome.SUCCESS, null);

        verify(completionService).completeClaimed(11L, fixture.session(), fixture.preparation(),
                GenerationTerminalOutcome.SUCCESS);
        verifyCommonCleanup(fixture, GenerationTerminalOutcome.SUCCESS);
    }

    @Test
    void lifecyclePersistenceFailureMustPreserveOwnedStateAndRuntimeForRecovery() {
        TerminalFixture fixture = fixture();
        doThrow(new IllegalStateException("persistence unavailable"))
                .when(completionService)
                .completeClaimed(11L, fixture.session(), fixture.preparation(), GenerationTerminalOutcome.FAILED);

        complete(fixture, GenerationTerminalOutcome.FAILED, new IllegalStateException("generation failed"));

        verify(sessionRegistry, never()).retainForReplay(11L, fixture.session());
        verify(executionContextService, never()).finishIfOwned(
                anyString(), any(), anyString());
    }

    @Test
    void cancellationClaimedWithoutRequestArgumentMustPublishCanonicalCancelledEvent() {
        TerminalFixture fixture = fixture();
        fixture.session().bindTaskRequest(fixture.request());

        complete(fixture, null, GenerationTerminalOutcome.CANCELLED, null);

        verify(eventPublisher).publishIdempotently(
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
        verify(eventPublisher, times(1)).publishIdempotently(
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
        verifyCommonCleanup(fixture, GenerationTerminalOutcome.FAILED);
    }

    private void verifyCommonCleanup(TerminalFixture fixture, GenerationTerminalOutcome outcome) {
        verify(performanceMonitorService).finishTask("task-11", outcome.status());
        verify(sessionRegistry).retainForReplay(11L, fixture.session());
        verify(sessionRegistry, never()).remove(11L, fixture.session());
        verify(toolExecutionContextService).clearContext(11L, "task-11");
        verify(executionContextService).finish("task-11", outcome.status());
    }

    @Test
    void fencedTerminalCleanupMustPreserveARecoveredExecutionEpoch() {
        GenerationPreparation preparation = preparation();
        GenerationExecutionFence fence = new GenerationExecutionFence("task-11", "worker-1", 7L);
        GenerationExecutionContext executionContext = org.mockito.Mockito.mock(GenerationExecutionContext.class);
        org.mockito.Mockito.when(executionContext.taskId()).thenReturn("task-11");
        org.mockito.Mockito.when(executionContext.executionFence()).thenReturn(fence);
        GenerationSession session = new GenerationSession(preparation, executionContext);
        TerminalFixture fixture = fixture(preparation, session);

        complete(fixture, GenerationTerminalOutcome.SUCCESS, null);

        verify(toolExecutionContextService).clearContext(11L, "task-11", fence);
        verify(toolExecutionContextService, never()).clearContext(11L, "task-11");
        verify(executionContextService).finishIfOwned("task-11", fence,
                GenerationTerminalOutcome.SUCCESS.status());
        verify(executionContextService, never()).finish("task-11",
                GenerationTerminalOutcome.SUCCESS.status());
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
        GenerationPreparation preparation = preparation();
        return fixture(preparation, new GenerationSession(preparation));
    }

    private GenerationPreparation preparation() {
        return new GenerationPreparation(
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
    }

    private TerminalFixture fixture(GenerationPreparation preparation, GenerationSession session) {
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
