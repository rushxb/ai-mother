package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DevServerValidationServiceTest {

    private DevServerManager manager;
    private DevServerRuntimeProperties properties;
    private GenerationExecutionContextService executionContextService;
    private DevServerValidationService service;
    private AtomicReference<DevServerErrorCollector> collectorReference;

    @BeforeEach
    void setUp() {
        manager = mock(DevServerManager.class);
        properties = new DevServerRuntimeProperties();
        properties.setValidationErrorCollectionWindow(Duration.ofMillis(1));
        properties.setValidationPollInterval(Duration.ofMillis(1));
        executionContextService = mock(GenerationExecutionContextService.class);
        when(executionContextService.clampTimeout(anyString(), any(Duration.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        service = new DevServerValidationService(manager, properties, executionContextService);
        collectorReference = new AtomicReference<>();
        doAnswer(invocation -> {
            collectorReference.set(invocation.getArgument(1));
            return null;
        }).when(manager).registerErrorCollector(eq(11L), any(DevServerErrorCollector.class));
    }

    @Test
    void invalidRequestMustFailBeforeRegisteringCollector() {
        assertThrows(BusinessException.class,
                () -> service.validate(" ", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT));
        assertThrows(BusinessException.class,
                () -> service.validate("task-1", 0L, 7L, CodeGenTypeEnum.VUE_PROJECT));
        assertThrows(BusinessException.class,
                () -> service.validate("task-1", 11L, 0L, CodeGenTypeEnum.VUE_PROJECT));
        assertThrows(BusinessException.class,
                () -> service.validate("task-1", 11L, 7L, null));

        verifyNoInteractions(manager);
        verifyNoInteractions(executionContextService);
    }

    @Test
    void newlyStartedValidationSessionMustBeStopped() {
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenReturn(new DevServerStartResult(5180, true));

        DevServerValidationResult result = service.validate(
                "task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT
        );

        assertEquals(DevServerValidationResult.ValidationStatus.PASS, result.status());
        verify(manager).stopDevServer(11L);
        verify(manager).unregisterErrorCollector(11L, collectorReference.get());
    }

    @Test
    void reusedValidationSessionMustNotBeStopped() {
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenReturn(new DevServerStartResult(5180, false));

        DevServerValidationResult result = service.validate(
                "task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT
        );

        assertTrue(result.isPassed());
        verify(manager, never()).stopDevServer(11L);
        verify(manager).unregisterErrorCollector(11L, collectorReference.get());
    }

    @Test
    void shouldPassFullStackGenerationTypeAndRuntimeControlsToManager() {
        Duration clampedStartupTimeout = Duration.ofSeconds(3);
        when(executionContextService.clampTimeout("task-1", properties.getStartupTimeout()))
                .thenReturn(clampedStartupTimeout);
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenReturn(new DevServerStartResult(5180, false));
        ArgumentCaptor<App> appCaptor = ArgumentCaptor.forClass(App.class);
        ArgumentCaptor<DevServerStartOptions> optionsCaptor = ArgumentCaptor.forClass(DevServerStartOptions.class);

        service.validate("task-1", 11L, 7L, CodeGenTypeEnum.FULL_STACK_PROJECT);

        verify(manager).startDevServer(appCaptor.capture(), eq(7L), optionsCaptor.capture());
        assertEquals(CodeGenTypeEnum.FULL_STACK_PROJECT.getValue(), appCaptor.getValue().getCodeGenType());
        assertEquals("task-1", optionsCaptor.getValue().taskId());
        assertEquals(clampedStartupTimeout, optionsCaptor.getValue().startupTimeout());
        when(executionContextService.shouldStop("task-1")).thenReturn(true);
        assertTrue(optionsCaptor.getValue().cancellationRequested().getAsBoolean());
    }

    @Test
    void missingProjectMustMapToSkipped() {
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR, "project missing"));

        DevServerValidationResult result = service.validate(
                "task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT
        );

        assertEquals(DevServerValidationResult.ValidationStatus.SKIPPED, result.status());
        verify(manager, never()).stopDevServer(11L);
        verify(manager).unregisterErrorCollector(11L, collectorReference.get());
    }

    @Test
    void startupTimeoutMustMapToTimeoutInsteadOfGenericFailure() {
        DevServerStartException cause = new DevServerStartException(
                DevServerStartException.Reason.STARTUP_TIMEOUT,
                "timeout"
        );
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenThrow(new BusinessException(ErrorCode.SYSTEM_ERROR, "timeout", cause));

        DevServerValidationResult result = service.validate(
                "task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT
        );

        assertEquals(DevServerValidationResult.ValidationStatus.TIMEOUT, result.status());
        verify(manager, never()).stopDevServer(11L);
    }

    @Test
    void dependencyOrProcessFailureMustMapToFailed() {
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenThrow(new BusinessException(ErrorCode.SYSTEM_ERROR, "dependency failed"));

        DevServerValidationResult result = service.validate(
                "task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT
        );

        assertEquals(DevServerValidationResult.ValidationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("dependency failed"));
    }

    @Test
    void managerWrappedCancellationMustRestoreRuntimePolicyException() {
        GenerationExecutionCancelledException cancellation =
                new GenerationExecutionCancelledException("user requested stop");
        doNothing().doThrow(cancellation)
                .when(executionContextService).assertCanContinue("task-1");
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "startup cancelled"));

        GenerationExecutionCancelledException thrown = assertThrows(
                GenerationExecutionCancelledException.class,
                () -> service.validate("task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT)
        );

        assertSame(cancellation, thrown);
        verify(manager).unregisterErrorCollector(11L, collectorReference.get());
    }

    @Test
    void collectedCriticalOutputMustFailValidation() {
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenAnswer(invocation -> {
                    collectorReference.get().feedLine("[vite] Internal server error: broken import");
                    return new DevServerStartResult(5180, false);
                });

        DevServerValidationResult result = service.validate(
                "task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT
        );

        assertEquals(DevServerValidationResult.ValidationStatus.FAILED, result.status());
        assertEquals(1, result.criticalErrorCount());
    }

    @Test
    void cancellationDuringCollectionMustPropagateAndCleanupOwnedSession() {
        GenerationExecutionCancelledException cancellation =
                new GenerationExecutionCancelledException("user requested stop");
        doNothing().doNothing().doThrow(cancellation)
                .when(executionContextService).assertCanContinue("task-1");
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenReturn(new DevServerStartResult(5180, true));

        GenerationExecutionCancelledException thrown = assertThrows(
                GenerationExecutionCancelledException.class,
                () -> service.validate("task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT)
        );

        assertSame(cancellation, thrown);
        verify(manager).stopDevServer(11L);
        verify(manager).unregisterErrorCollector(11L, collectorReference.get());
    }

    @Test
    void deadlineDuringCollectionMustPropagateAndCleanupOwnedSession() {
        GenerationDeadlineExceededException deadline = new GenerationDeadlineExceededException("task-1");
        doNothing().doNothing().doThrow(deadline)
                .when(executionContextService).assertCanContinue("task-1");
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenReturn(new DevServerStartResult(5180, true));

        GenerationDeadlineExceededException thrown = assertThrows(
                GenerationDeadlineExceededException.class,
                () -> service.validate("task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT)
        );

        assertSame(deadline, thrown);
        verify(manager).stopDevServer(11L);
        verify(manager).unregisterErrorCollector(11L, collectorReference.get());
    }

    @Test
    void cancellationAfterReusingSessionMustNotStopForeignSession() {
        GenerationExecutionCancelledException cancellation =
                new GenerationExecutionCancelledException("user requested stop");
        doNothing().doThrow(cancellation)
                .when(executionContextService).assertCanContinue("task-1");
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenReturn(new DevServerStartResult(5180, false));

        GenerationExecutionCancelledException thrown = assertThrows(
                GenerationExecutionCancelledException.class,
                () -> service.validate("task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT)
        );

        assertSame(cancellation, thrown);
        verify(manager, never()).stopDevServer(11L);
        verify(manager).unregisterErrorCollector(11L, collectorReference.get());
    }

    @Test
    void interruptedCollectionWindowMustRestoreInterruptFlagAndReturnFailure() throws Exception {
        properties.setValidationErrorCollectionWindow(Duration.ofSeconds(5));
        properties.setValidationPollInterval(Duration.ofMillis(10));
        CountDownLatch startupCompleted = new CountDownLatch(1);
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenAnswer(invocation -> {
                    startupCompleted.countDown();
                    return new DevServerStartResult(5180, true);
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Thread> validationThread = new AtomicReference<>();
            Future<ValidationThreadOutcome> future = executor.submit(() -> {
                validationThread.set(Thread.currentThread());
                DevServerValidationResult result = service.validate(
                        "task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT
                );
                return new ValidationThreadOutcome(result, Thread.currentThread().isInterrupted());
            });

            assertTrue(startupCompleted.await(1, TimeUnit.SECONDS));
            validationThread.get().interrupt();
            ValidationThreadOutcome outcome = future.get(1, TimeUnit.SECONDS);

            assertEquals(DevServerValidationResult.ValidationStatus.FAILED, outcome.result().status());
            assertTrue(outcome.interrupted());
            verify(manager).stopDevServer(11L);
            verify(manager).unregisterErrorCollector(11L, collectorReference.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private record ValidationThreadOutcome(DevServerValidationResult result, boolean interrupted) {
    }
}
