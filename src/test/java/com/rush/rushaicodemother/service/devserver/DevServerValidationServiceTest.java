package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DevServerValidationServiceTest {

    private DevServerManager manager;
    private DevServerRuntimeProperties properties;
    private DevServerValidationService service;
    private AtomicReference<DevServerErrorCollector> collectorReference;

    @BeforeEach
    void setUp() {
        manager = mock(DevServerManager.class);
        properties = new DevServerRuntimeProperties();
        properties.setValidationErrorCollectionWindow(Duration.ofMillis(1));
        service = new DevServerValidationService(manager, properties);
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
    }

    @Test
    void newlyStartedValidationSessionMustBeStopped() {
        when(manager.startDevServer(any(App.class), eq(7L)))
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
        when(manager.startDevServer(any(App.class), eq(7L)))
                .thenReturn(new DevServerStartResult(5180, false));

        DevServerValidationResult result = service.validate(
                "task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT
        );

        assertTrue(result.isPassed());
        verify(manager, never()).stopDevServer(11L);
        verify(manager).unregisterErrorCollector(11L, collectorReference.get());
    }

    @Test
    void shouldPassFullStackGenerationTypeToManager() {
        when(manager.startDevServer(any(App.class), eq(7L)))
                .thenReturn(new DevServerStartResult(5180, false));
        ArgumentCaptor<App> appCaptor = ArgumentCaptor.forClass(App.class);

        service.validate("task-1", 11L, 7L, CodeGenTypeEnum.FULL_STACK_PROJECT);

        verify(manager).startDevServer(appCaptor.capture(), eq(7L));
        assertEquals(CodeGenTypeEnum.FULL_STACK_PROJECT.getValue(), appCaptor.getValue().getCodeGenType());
    }

    @Test
    void missingProjectMustMapToSkipped() {
        when(manager.startDevServer(any(App.class), eq(7L)))
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
        when(manager.startDevServer(any(App.class), eq(7L)))
                .thenThrow(new BusinessException(ErrorCode.SYSTEM_ERROR, "timeout", cause));

        DevServerValidationResult result = service.validate(
                "task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT
        );

        assertEquals(DevServerValidationResult.ValidationStatus.TIMEOUT, result.status());
        verify(manager, never()).stopDevServer(11L);
    }

    @Test
    void dependencyOrProcessFailureMustMapToFailed() {
        when(manager.startDevServer(any(App.class), eq(7L)))
                .thenThrow(new BusinessException(ErrorCode.SYSTEM_ERROR, "dependency failed"));

        DevServerValidationResult result = service.validate(
                "task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT
        );

        assertEquals(DevServerValidationResult.ValidationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("dependency failed"));
    }

    @Test
    void collectedCriticalOutputMustFailValidation() {
        when(manager.startDevServer(any(App.class), eq(7L))).thenAnswer(invocation -> {
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
    void interruptedCollectionWindowMustRestoreInterruptFlagAndReturnFailure() throws Exception {
        properties.setValidationErrorCollectionWindow(Duration.ofSeconds(5));
        CountDownLatch startupCompleted = new CountDownLatch(1);
        when(manager.startDevServer(any(App.class), eq(7L))).thenAnswer(invocation -> {
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
