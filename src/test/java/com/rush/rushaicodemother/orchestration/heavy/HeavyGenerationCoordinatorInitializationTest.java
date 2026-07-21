package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTerminalOutcome;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.identity.GenerationTaskIdGenerator;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.GenerationApprovalRequiredException;
import com.rush.rushaicodemother.orchestration.tool.DestructiveToolAction;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HeavyGenerationCoordinatorInitializationTest {

    private static final String TASK_ID = "task-runtime-1";
    private static final long APP_ID = 11L;
    private static final long USER_ID = 22L;
    private static final GenerationExecutionFence FENCE =
            new GenerationExecutionFence(TASK_ID, "worker-a", 3L);

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
    private GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    @Mock
    private GenerationTraceService traceService;
    @Mock
    private GenerationExecutionContextService executionContextService;
    @Mock
    private GenerationTaskIdGenerator taskIdGenerator;
    @Mock
    private GenerationExecutionContext executionContext;
    @Mock
    private GenerationPerformanceMonitorService.SpanTimer spanTimer;

    private HeavyGenerationCoordinator coordinator;
    private GenerationPipelineRequest pipelineRequest;
    private GenerationTaskRequest taskRequest;

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
                runtimeLifecycleService,
                traceService,
                executionContextService,
                taskIdGenerator
        );
        when(sessionRegistry.lock(APP_ID)).thenReturn(new Object());
        when(taskIdGenerator.nextId()).thenReturn(TASK_ID);

        App app = new App();
        app.setId(APP_ID);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        User user = new User();
        user.setId(USER_ID);
        taskRequest = new GenerationTaskRequest(app, "build a dashboard", user);
        GenerationModeDecision modeDecision = new GenerationModeDecision(
                GenerationMode.HEAVY_EXPERT,
                1.0,
                "test",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.EXPERT,
                ""
        );
        pipelineRequest = new GenerationPipelineRequest(
                taskRequest,
                CodeGenTypeEnum.VUE_PROJECT,
                null,
                modeDecision
        );
    }

    @Test
    void runtimeMustStartBeforePreparationAndPreparationFailureMustBeCompensated() {
        IllegalStateException preparationFailure = new IllegalStateException("preparation failed");
        when(executionContextService.start(TASK_ID, APP_ID, USER_ID)).thenReturn(executionContext);
        when(preparationService.prepare(TASK_ID, taskRequest.app(), taskRequest.message()))
                .thenThrow(preparationFailure);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> coordinator.start(pipelineRequest));

        assertSame(preparationFailure, thrown);
        InOrder initializationOrder = inOrder(executionContextService, preparationService);
        initializationOrder.verify(executionContextService).start(TASK_ID, APP_ID, USER_ID);
        initializationOrder.verify(preparationService).prepare(TASK_ID, taskRequest.app(), taskRequest.message());
        verify(performanceMonitorService).recordSpan(
                eq(TASK_ID), eq("heavy_prepare"), eq("failed"), any(Duration.class),
                eq(IllegalStateException.class.getSimpleName())
        );
        verify(performanceMonitorService).finishTask(TASK_ID, GenerationTerminalOutcome.FAILED.status());
        verify(toolExecutionContextService).clearContext(APP_ID, TASK_ID);
        verify(eventPublisher).publishSafely(
                same(taskRequest),
                eq(GenerationEventType.TASK_FAILED),
                eq(GenerationTerminalOutcome.FAILED.eventMessage()),
                anyMap()
        );
        verify(executionContextService).finish(TASK_ID, GenerationTerminalOutcome.FAILED.status());
        verifyNoInteractions(lifecycleService);
    }

    @Test
    void deadlineAfterPreparationMustUseTimedOutTerminalOutcomeWithoutOpeningSession() {
        GenerationDeadlineExceededException deadlineFailure = new GenerationDeadlineExceededException(TASK_ID);
        when(executionContextService.start(TASK_ID, APP_ID, USER_ID)).thenReturn(executionContext);
        when(preparationService.prepare(TASK_ID, taskRequest.app(), taskRequest.message()))
                .thenReturn(preparation(TASK_ID));
        org.mockito.Mockito.doThrow(deadlineFailure).when(executionContext).assertCanContinue();

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> coordinator.start(pipelineRequest));

        assertSame(deadlineFailure, thrown);
        verify(performanceMonitorService).recordSpan(
                eq(TASK_ID), eq("heavy_prepare"), eq("failed"), any(Duration.class),
                eq(GenerationDeadlineExceededException.class.getSimpleName())
        );
        verify(performanceMonitorService).finishTask(
                TASK_ID, GenerationTerminalOutcome.DEADLINE_EXCEEDED.status());
        verify(eventPublisher).publishSafely(
                same(taskRequest),
                eq(GenerationEventType.TASK_TIMED_OUT),
                eq(GenerationTerminalOutcome.DEADLINE_EXCEEDED.eventMessage()),
                anyMap()
        );
        verify(executionContextService).finish(
                TASK_ID, GenerationTerminalOutcome.DEADLINE_EXCEEDED.status());
        verifyNoInteractions(lifecycleService);
        verify(sessionRegistry, never()).put(any(), any());
    }

    @Test
    void runtimeConflictMustPreservePolicyExceptionAndMustNotCleanAnotherTask() {
        GenerationExecutionPolicyException policyFailure =
                new GenerationExecutionPolicyException("application already has an active runtime");
        when(executionContextService.start(TASK_ID, APP_ID, USER_ID)).thenThrow(policyFailure);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> coordinator.start(pipelineRequest));

        assertSame(policyFailure, thrown);
        verify(preparationService, never()).prepare(any(), any(), any());
        verify(performanceMonitorService, never()).startTask(
                any(), any(), any(), any(), any(), any(), any()
        );
        verify(toolExecutionContextService, never()).clearContext(any());
        verify(eventPublisher, never()).publishSafely(any(), any(), any(), anyMap());
        verify(executionContextService, never()).finish(any(), any());
    }

    @Test
    void approvalSignalMustSuspendDurableTaskWithoutTerminalizingSession() {
        String approvalId = "a".repeat(64);
        GenerationApprovalRequiredException required = new GenerationApprovalRequiredException(
                TASK_ID,
                DestructiveToolAction.SNAPSHOT_ROLLBACK,
                approvalId,
                Map.of("snapshotName", "safe")
        );
        when(executionContextService.start(TASK_ID, APP_ID, USER_ID)).thenReturn(executionContext);
        when(executionContext.executionFence()).thenReturn(FENCE);
        when(preparationService.prepare(TASK_ID, taskRequest.app(), taskRequest.message()))
                .thenReturn(preparation(TASK_ID));
        when(performanceMonitorService.startSpan(
                TASK_ID, "llm_generation",
                com.rush.rushaicodemother.monitor.span.GenerationSpanCategory.MODEL))
                .thenReturn(spanTimer);
        org.mockito.Mockito.doThrow(required).when(executionService)
                .runGenerationWithAutoRepair(
                        eq(APP_ID), same(taskRequest.loginUser()), any(), any());
        when(runtimeLifecycleService.suspendForApproval(
                FENCE, "approval_required:rollbackSnapshot")).thenReturn(true);

        coordinator.start(pipelineRequest);

        verify(spanTimer).close("suspended", "approval_required");
        verify(runtimeLifecycleService).suspendForApproval(
                FENCE, "approval_required:rollbackSnapshot");
        verify(completionService, never()).completeClaimed(any(), any(), any(), any());
        verify(executionContextService, never()).finish(eq(TASK_ID), any());
    }

    private GenerationPreparation preparation(String taskId) {
        return new GenerationPreparation(
                CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeEnum.VUE_PROJECT,
                false,
                "build",
                "enhanced prompt",
                List.of(),
                Map.of(),
                null,
                Map.of(),
                taskId
        );
    }
}
