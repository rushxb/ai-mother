package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.eventstream.GenerationEventStream;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaEnvelope;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaPolicy;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContext;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContextBridge;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
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
    private GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private GenerationEventStream eventStream;
    private GenerationSlaPolicy generationSlaPolicy;
    private GenerationTraceContextBridge traceContextBridge;

    @BeforeEach
    void setUp() {
        dispatcher = mock(GenerationTaskDispatcher.class);
        admissionService = mock(GenerationTaskAdmissionService.class);
        runtimeLifecycleService = mock(GenerationTaskRuntimeLifecycleService.class);
        eventStream = mock(GenerationEventStream.class);
        traceContextBridge = mock(GenerationTraceContextBridge.class);
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
    }

    @Test
    void submitMustPersistReconstructableCommandBeforeDispatchAndReturnTaskStream() {
        Flux<?> expectedStream = Flux.empty();
        when(eventStream.stream("task-submit-1")).thenReturn((Flux) expectedStream);
        GenerationTaskSubmissionService service = service("task-submit-1");

        GenerationTaskResult result = service.submit(request(1L));

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
        assertEquals("task-submit-1", result.taskId());
        assertEquals(GenerationTaskStatus.QUEUED, result.submission().status());
        assertEquals(NOW, result.submission().submittedAt());
        assertEquals(NOW.plus(Duration.ofMinutes(12)), result.submission().deadlineAt());
        assertSame(expectedStream, result.contentFlux());
    }

    @Test
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
        verify(runtimeLifecycleService, never()).completeUnowned(
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
        verify(runtimeLifecycleService, never()).completeUnowned(
                "task-concurrent", GenerationTaskStatus.FAILED, "submission_failed");
    }

    @Test
    void dispatcherFailureMustCompensatePersistedTaskAsFailed() {
        doThrow(new IllegalStateException("local executor unavailable"))
                .when(dispatcher).dispatch("task-rejected");
        GenerationTaskSubmissionService service = service("task-rejected");

        assertThrows(IllegalStateException.class, () -> service.submit(request(1L)));

        verify(runtimeLifecycleService).completeUnowned(
                "task-rejected", GenerationTaskStatus.FAILED, "submission_failed");
        verify(eventStream, never()).stream("task-rejected");
    }

    private GenerationTaskSubmissionService service(String taskId) {
        return service(taskId, null);
    }

    private GenerationTaskSubmissionService service(String taskId,
                                                    GenerationEventPublisher recentPublisher) {
        return new GenerationTaskSubmissionService(
                () -> taskId,
                generationSlaPolicy,
                dispatcher,
                admissionService,
                runtimeLifecycleService,
                eventStream,
                recentPublisher,
                traceContextBridge,
                Clock.fixed(NOW, ZoneOffset.UTC));
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
