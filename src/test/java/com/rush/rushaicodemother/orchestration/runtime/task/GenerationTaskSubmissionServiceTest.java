package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.decision.GenerationPreflightUsage;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioPreflight;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioPreflightResult;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTaskFinalizer;
import com.rush.rushaicodemother.orchestration.eventstream.GenerationEventStream;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlanner;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaEnvelope;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaPolicy;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContext;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContextBridge;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.Optional;

import static com.rush.rushaicodemother.testing.GenerationReleaseSmoke.TAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationTaskSubmissionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-17T02:00:00Z");

    private GenerationTaskDispatcher dispatcher;
    private GenerationTaskAdmissionService admissionService;
    private GenerationTaskFinalizer taskFinalizer;
    private GenerationEventStream eventStream;
    private GenerationSlaPolicy generationSlaPolicy;
    private GenerationExecutionPlanner executionPlanner;
    private GenerationTraceContextBridge traceContextBridge;

    @BeforeEach
    void setUp() {
        dispatcher = mock(GenerationTaskDispatcher.class);
        admissionService = mock(GenerationTaskAdmissionService.class);
        taskFinalizer = mock(GenerationTaskFinalizer.class);
        eventStream = mock(GenerationEventStream.class);
        traceContextBridge = mock(GenerationTraceContextBridge.class);
        executionPlanner = mock(GenerationExecutionPlanner.class);
        when(admissionService.admit(any(GenerationTaskCommand.class), any(GenerationTaskIdempotency.class)))
                .thenAnswer(invocation -> {
                    GenerationTaskCommand command = invocation.getArgument(0);
                    return GenerationTaskAdmissionResult.created(
                            GenerationTaskSubmissionReceipt.queued(command));
                });
        when(traceContextBridge.capture()).thenReturn(new GenerationTraceContext(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", null));
        GenerationRuntimeProperties runtimeProperties = new GenerationRuntimeProperties();
        runtimeProperties.setTaskTimeout(Duration.ofMinutes(12));
        generationSlaPolicy = (decision, targetType) -> new GenerationSlaEnvelope(
                "test-profile",
                Duration.ofMinutes(1),
                Duration.ofMinutes(12),
                runtimeProperties.getModelCallTimeout(),
                runtimeProperties.getMinimumOperationTimeout(),
                runtimeProperties.toLimits().budgets(),
                "test"
        );
        when(executionPlanner.plan(any(GenerationPipelineRequest.class),
                any(GenerationPreflightUsage.class)))
                .thenAnswer(invocation -> plan(invocation.getArgument(0)));
    }

    @Test
    void invalidSubmissionInputMustUseChineseMessage() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service("task-invalid").submit(null));

        assertTrue(exception.getMessage().matches(".*[\\u4e00-\\u9fff].*"));
    }

    @Test
    void submitMustPersistReconstructableCommandBeforeDispatchAndReturnTaskStream() {
        Flux<?> expectedStream = Flux.empty();
        when(eventStream.stream("task-submit-1")).thenReturn((Flux) expectedStream);
        GenerationTaskSubmissionService service = service("task-submit-1");

        GenerationPipelineRequest request = request(1L);
        GenerationTaskResult result = service.submit(request);

        ArgumentCaptor<GenerationTaskCommand> commandCaptor =
                ArgumentCaptor.forClass(GenerationTaskCommand.class);
        InOrder order = inOrder(admissionService, dispatcher, eventStream);
        order.verify(admissionService).admit(
                commandCaptor.capture(), org.mockito.ArgumentMatchers.eq(GenerationTaskIdempotency.none()));
        order.verify(dispatcher).dispatch("task-submit-1");
        order.verify(eventStream).stream("task-submit-1");

        GenerationTaskCommand command = commandCaptor.getValue();
        assertEquals("task-submit-1", command.taskId());
        assertEquals(1L, command.appId());
        assertEquals(2L, command.userId());
        assertEquals(100L, command.tenantId());
        assertEquals("update title", command.userPrompt());
        assertEquals(NOW, command.submittedAt());
        assertEquals(NOW.plus(Duration.ofMinutes(12)), command.deadlineAt());
        assertEquals("test-profile", command.slaEnvelope().profile());
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                command.traceContext().traceparent());
        assertEquals("lightweight_edit", command.route());
        assertEquals(command.executionPlan().sla(), command.slaEnvelope());
        verify(executionPlanner).plan(request, GenerationPreflightUsage.none());
        assertEquals("task-submit-1", result.taskId());
        assertEquals(GenerationTaskStatus.QUEUED, result.submission().status());
        assertEquals(NOW, result.submission().submittedAt());
        assertEquals(NOW.plus(Duration.ofMinutes(12)), result.submission().deadlineAt());
        assertSame(expectedStream, result.contentFlux());
    }

    @Test
    @Tag(TAG)
    void idempotentReplayMustReturnOriginalTaskWithoutDispatchOrCompensation() {
        GenerationTaskIdempotency idempotency =
                new GenerationTaskIdempotency("a".repeat(64), "b".repeat(64));
        when(admissionService.admit(any(GenerationTaskCommand.class),
                org.mockito.ArgumentMatchers.eq(idempotency)))
                .thenReturn(GenerationTaskAdmissionResult.reused(
                        new GenerationTaskSubmissionReceipt(
                                "task-original",
                                1L,
                                "heavy_generation",
                                GenerationTaskStatus.RUNNING,
                                NOW.minusSeconds(30),
                                NOW.plus(Duration.ofMinutes(10))
                        )));
        Flux<?> expectedStream = Flux.empty();
        when(eventStream.stream("task-original")).thenReturn((Flux) expectedStream);
        GenerationEventPublisher recentPublisher = mock(GenerationEventPublisher.class);
        GenerationTaskSubmissionService service = service("task-unused-candidate", recentPublisher);

        GenerationTaskResult result = service.submit(request(1L), idempotency);

        assertEquals("task-original", result.taskId());
        assertEquals("heavy_generation", result.route());
        assertEquals(GenerationTaskStatus.RUNNING, result.submission().status());
        assertEquals(NOW.minusSeconds(30), result.submission().submittedAt());
        assertFalse(result.created());
        assertSame(expectedStream, result.contentFlux());
        verify(dispatcher, never()).dispatch(org.mockito.ArgumentMatchers.anyString());
        verify(taskFinalizer, never()).finalizeUnownedRuntime(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(GenerationTaskStatus.class),
                org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(recentPublisher);
    }

    @Test
    void durableSubmissionRejectionMustNotDispatchOrCreateEventSubscription() {
        BusinessException rejection = mock(BusinessException.class);
        doThrow(rejection).when(admissionService).admit(
                any(GenerationTaskCommand.class), any(GenerationTaskIdempotency.class));
        GenerationTaskSubmissionService service = service("task-concurrent");

        assertSame(rejection, assertThrows(BusinessException.class, () -> service.submit(request(1L))));

        verify(dispatcher, never()).dispatch("task-concurrent");
        verify(eventStream, never()).stream("task-concurrent");
        verify(taskFinalizer, never()).finalizeUnownedRuntime(
                "task-concurrent", GenerationTaskStatus.FAILED, "submission_failed");
    }

    @Test
    void dispatcherFailureMustCompensatePersistedTaskAsFailed() {
        doThrow(new IllegalStateException("local executor unavailable"))
                .when(dispatcher).dispatch("task-rejected");
        GenerationTaskSubmissionService service = service("task-rejected");

        assertThrows(IllegalStateException.class, () -> service.submit(request(1L)));

        verify(taskFinalizer).finalizeUnownedRuntime(
                "task-rejected", GenerationTaskStatus.FAILED, "submission_failed");
        verify(eventStream, never()).stream("task-rejected");
    }

    @Test
    void transientLocalCapacityMustKeepDurableTaskQueuedForRedispatch() {
        GenerationTaskCommandExecutionService executionService =
                mock(GenerationTaskCommandExecutionService.class);
        when(executionService.schedule("task-local-deferred", null))
                .thenReturn(GenerationTaskDispatchResult.RETRY);
        when(eventStream.stream("task-local-deferred")).thenReturn(Flux.empty());
        GenerationTaskDispatcher localDispatcher =
                new LocalGenerationTaskDispatcher(executionService);
        GenerationTaskSubmissionService service = new GenerationTaskSubmissionService(
                () -> "task-local-deferred",
                executionPlanner,
                localDispatcher,
                admissionService,
                taskFinalizer,
                eventStream,
                null,
                traceContextBridge,
                Clock.fixed(NOW, ZoneOffset.UTC));

        GenerationTaskResult result = service.submit(request(1L));

        assertEquals(GenerationTaskStatus.QUEUED, result.submission().status());
        assertTrue(result.created());
        verify(taskFinalizer, never()).finalizeUnownedRuntime(
                "task-local-deferred", GenerationTaskStatus.FAILED, "submission_failed");
    }

    @Test
    void primarySubmissionMustPreflightAfterIdentityAndPersistItsUsage() {
        GenerationScenarioPreflight preflight = mock(GenerationScenarioPreflight.class);
        GenerationPipelineRequest input = request(1L);
        GenerationPreflightUsage usage = new GenerationPreflightUsage(1, 1, 2);
        when(eventStream.stream("task-preflight-submit")).thenReturn(Flux.empty());
        when(preflight.prepare(
                eq("task-preflight-submit"), eq(NOW), eq(input.taskRequest()),
                eq(input.codeGenType()), eq(input.workspace())))
                .thenReturn(new GenerationScenarioPreflightResult(
                        input.scenarioDecision(), usage, true));
        GenerationTaskSubmissionService service = new GenerationTaskSubmissionService(
                () -> "task-preflight-submit",
                preflight,
                executionPlanner,
                dispatcher,
                admissionService,
                taskFinalizer,
                eventStream,
                null,
                traceContextBridge,
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.submit(input.taskRequest(), input.codeGenType(), input.workspace(),
                GenerationTaskIdempotency.none());

        ArgumentCaptor<GenerationTaskCommand> commandCaptor =
                ArgumentCaptor.forClass(GenerationTaskCommand.class);
        InOrder order = inOrder(preflight, executionPlanner, admissionService);
        order.verify(admissionService).findIdempotentReplay(
                input.taskRequest(), GenerationTaskIdempotency.none());
        order.verify(preflight).prepare(
                "task-preflight-submit", NOW, input.taskRequest(),
                input.codeGenType(), input.workspace());
        order.verify(executionPlanner).plan(any(GenerationPipelineRequest.class), eq(usage));
        order.verify(admissionService).admit(
                commandCaptor.capture(), eq(GenerationTaskIdempotency.none()));
        assertEquals(usage, commandCaptor.getValue().preflightUsage());
        assertEquals(input.scenarioDecision(), commandCaptor.getValue().scenarioDecision());
    }

    @Test
    void primarySubmissionMustPersistPreflightResolvedTargetType() {
        GenerationScenarioPreflight preflight = mock(GenerationScenarioPreflight.class);
        GenerationPipelineRequest input = request(1L);
        GenerationScenarioDecision resolvedDecision = withTargetType(
                input.scenarioDecision(), CodeGenTypeEnum.FULL_STACK_PROJECT);
        when(eventStream.stream("task-target-submit")).thenReturn(Flux.empty());
        when(preflight.prepare(
                eq("task-target-submit"), eq(NOW), eq(input.taskRequest()),
                eq(input.codeGenType()), eq(input.workspace())))
                .thenReturn(new GenerationScenarioPreflightResult(
                        resolvedDecision, GenerationPreflightUsage.none(), false));
        GenerationTaskSubmissionService service = new GenerationTaskSubmissionService(
                () -> "task-target-submit",
                preflight,
                executionPlanner,
                dispatcher,
                admissionService,
                taskFinalizer,
                eventStream,
                null,
                traceContextBridge,
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.submit(input.taskRequest(), input.codeGenType(), input.workspace(),
                GenerationTaskIdempotency.none());

        ArgumentCaptor<GenerationTaskCommand> commandCaptor =
                ArgumentCaptor.forClass(GenerationTaskCommand.class);
        verify(admissionService).admit(
                commandCaptor.capture(), eq(GenerationTaskIdempotency.none()));
        GenerationTaskCommand command = commandCaptor.getValue();
        assertEquals(CodeGenTypeEnum.FULL_STACK_PROJECT, command.codeGenType());
        assertEquals(CodeGenTypeEnum.FULL_STACK_PROJECT,
                command.scenarioDecision().targetType());
    }

    @Test
    void finalAdmissionFailureMustSettleTheOrphanPreflightReservation() {
        GenerationScenarioPreflight preflight = mock(GenerationScenarioPreflight.class);
        GenerationPipelineRequest input = request(1L);
        BusinessException rejection = mock(BusinessException.class);
        when(preflight.prepare(
                eq("task-preflight-rejected"), eq(NOW), eq(input.taskRequest()),
                eq(input.codeGenType()), eq(input.workspace())))
                .thenReturn(new GenerationScenarioPreflightResult(
                        input.scenarioDecision(), new GenerationPreflightUsage(1, 1, 1), true));
        doThrow(rejection).when(admissionService).admit(
                any(GenerationTaskCommand.class), eq(GenerationTaskIdempotency.none()));
        GenerationTaskSubmissionService service = new GenerationTaskSubmissionService(
                () -> "task-preflight-rejected",
                preflight,
                executionPlanner,
                dispatcher,
                admissionService,
                taskFinalizer,
                eventStream,
                null,
                traceContextBridge,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertSame(rejection, assertThrows(BusinessException.class, () -> service.submit(
                input.taskRequest(), input.codeGenType(), input.workspace(),
                GenerationTaskIdempotency.none())));

        verify(admissionService).settlePreflightReservation("task-preflight-rejected");
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void primaryIdempotentReplayMustReturnBeforePreflightModelCall() {
        GenerationScenarioPreflight preflight = mock(GenerationScenarioPreflight.class);
        GenerationPipelineRequest input = request(1L);
        GenerationTaskIdempotency idempotency =
                new GenerationTaskIdempotency("d".repeat(64), "e".repeat(64));
        GenerationTaskSubmissionReceipt receipt = new GenerationTaskSubmissionReceipt(
                "task-existing", 1L, "heavy_generation", GenerationTaskStatus.RUNNING,
                NOW.minusSeconds(10), NOW.plusSeconds(300));
        when(admissionService.findIdempotentReplay(input.taskRequest(), idempotency))
                .thenReturn(Optional.of(receipt));
        when(eventStream.stream("task-existing")).thenReturn(Flux.empty());
        GenerationTaskSubmissionService service = new GenerationTaskSubmissionService(
                () -> "must-not-allocate",
                preflight,
                executionPlanner,
                dispatcher,
                admissionService,
                taskFinalizer,
                eventStream,
                null,
                traceContextBridge,
                Clock.fixed(NOW, ZoneOffset.UTC));

        GenerationTaskResult result = service.submit(
                input.taskRequest(), input.codeGenType(), input.workspace(), idempotency);

        assertEquals("task-existing", result.taskId());
        assertFalse(result.created());
        verifyNoInteractions(preflight);
        verify(executionPlanner, never()).plan(any(), any());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void preflightCancellationMustNotPlanAdmitOrDispatchDurableTask() {
        GenerationScenarioPreflight preflight = mock(GenerationScenarioPreflight.class);
        GenerationPipelineRequest input = request(1L);
        GenerationExecutionCancelledException cancellation =
                new GenerationExecutionCancelledException("user_requested");
        when(preflight.prepare(
                eq("task-preflight-cancelled"), eq(NOW), eq(input.taskRequest()),
                eq(input.codeGenType()), eq(input.workspace())))
                .thenThrow(cancellation);
        GenerationTaskSubmissionService service = new GenerationTaskSubmissionService(
                () -> "task-preflight-cancelled",
                preflight,
                executionPlanner,
                dispatcher,
                admissionService,
                taskFinalizer,
                eventStream,
                null,
                traceContextBridge,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertSame(cancellation, assertThrows(
                GenerationExecutionCancelledException.class,
                () -> service.submit(
                        input.taskRequest(), input.codeGenType(), input.workspace(),
                        GenerationTaskIdempotency.none())));

        verify(executionPlanner, never()).plan(any(), any());
        verify(admissionService, never()).admit(any(), any());
        verify(dispatcher, never()).dispatch(any());
        verifyNoInteractions(taskFinalizer);
    }

    private GenerationTaskSubmissionService service(String taskId) {
        return service(taskId, null);
    }

    private GenerationTaskSubmissionService service(String taskId,
                                                    GenerationEventPublisher recentPublisher) {
        return new GenerationTaskSubmissionService(
                () -> taskId,
                executionPlanner,
                dispatcher,
                admissionService,
                taskFinalizer,
                eventStream,
                recentPublisher,
                traceContextBridge,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private GenerationExecutionPlan plan(GenerationPipelineRequest request) {
        GenerationSlaEnvelope sla = generationSlaPolicy.resolve(
                request.modeDecision(), request.codeGenType());
        GenerationPerformanceProfile modelProfile = GenerationPerformanceProfile.balanced();
        return new GenerationExecutionPlan(
                request.modeDecision(),
                modelProfile,
                new GenerationExecutionPlan.ContextBudget(
                        2_000, 1_500, 800, 64, 6, "gpt-4o", 1.15),
                new GenerationExecutionPlan.ToolPolicy(
                        modelProfile.maxToolInvocations(),
                        sla.toLimits().limit(GenerationBudgetKind.TOOL_WRITE),
                        true,
                        true),
                GenerationExecutionPlan.ValidationGraph.forLevel(
                        request.modeDecision().expectedValidationLevel()),
                new GenerationExecutionPlan.RepairBudget(
                        sla.toLimits().limit(GenerationBudgetKind.REPAIR_ROUND), true),
                new GenerationExecutionPlan.CommitPolicy(true, true),
                new GenerationExecutionPlan.PreviewPolicy(
                        sla.firstPreviewTimeout(), sla.firstPreviewCompletionReserve()),
                sla
        );
    }

    private GenerationScenarioDecision withTargetType(GenerationScenarioDecision source,
                                                      CodeGenTypeEnum targetType) {
        return new GenerationScenarioDecision(
                source.intentProfile(),
                targetType,
                source.mutability(),
                source.requiredResources(),
                source.routeDecision(),
                source.toolPermissionProfile(),
                source.ruleVersion(),
                source.releaseFingerprint());
    }

    private GenerationPipelineRequest request(Long appId) {
        App app = new App();
        app.setId(appId);
        app.setTenantId(100L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        User user = new User();
        user.setId(2L);
        Path root = Path.of("target/submission-test", appId.toString()).toAbsolutePath().normalize();
        GenerationWorkspace workspace = new GenerationWorkspace(
                appId, CodeGenTypeEnum.VUE_PROJECT, root, root, true, root, root, Set.of(), Set.of());
        GenerationModeDecision decision = GenerationModeDecision.of(
                GenerationMode.LIGHT_EDIT, 0.9, "test", FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD);
        return new GenerationPipelineRequest(
                new GenerationTaskRequest(app, "update title", user),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace,
                decision);
    }
}
