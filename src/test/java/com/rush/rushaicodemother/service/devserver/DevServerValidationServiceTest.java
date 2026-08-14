package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeObservation;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeProbe;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationPolicy;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DevServerValidationServiceTest {

    private DevServerManager manager;
    private DevServerRuntimeProperties properties;
    private GenerationExecutionContextService executionContextService;
    private BrowserRuntimeProbe browserRuntimeProbe;
    private DevServerValidationService service;
    private AtomicReference<DevServerErrorCollector> collectorReference;

    @BeforeEach
    void setUp() {
        manager = mock(DevServerManager.class);
        properties = new DevServerRuntimeProperties();
        properties.setValidationErrorCollectionWindow(Duration.ofMillis(1));
        properties.setValidationCriticalErrorDrainWindow(Duration.ofMillis(1));
        properties.setValidationPollInterval(Duration.ofMillis(1));
        executionContextService = mock(GenerationExecutionContextService.class);
        when(executionContextService.clampTimeout(anyString(), any(Duration.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        browserRuntimeProbe = mock(BrowserRuntimeProbe.class);
        service = new DevServerValidationService(
                manager,
                properties,
                executionContextService,
                new BrowserRuntimeVerifier(browserRuntimeProbe)
        );
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
    void requestedBrowserNetworkValidationMustRunWithBackendEnvironmentAndBlockFailure() {
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenReturn(new DevServerStartResult(5_180, true));
        URI frontendUri = URI.create("http://127.0.0.1:5180/");
        when(browserRuntimeProbe.inspect(frontendUri, Duration.ZERO)).thenReturn(
                browserObservationWithNetworkFailure(frontendUri)
        );
        ArgumentCaptor<DevServerStartOptions> options =
                ArgumentCaptor.forClass(DevServerStartOptions.class);

        DevServerValidationResult result = service.validate(
                DevServerValidationRequest.of(
                                "task-browser", 11L, 7L, CodeGenTypeEnum.FULL_STACK_PROJECT)
                        .withEnvironmentOverrides(Map.of(
                                "VITE_API_BASE_URL", "http://127.0.0.1:19101/api"))
                        .withBrowserValidation(new BrowserRuntimeValidationPolicy(
                                Duration.ZERO, false))
        );

        assertEquals(DevServerValidationResult.ValidationStatus.FAILED, result.status());
        assertEquals(
                DevServerValidationResult.ValidationFailureKind.BROWSER_RUNTIME_ERROR,
                result.failureKind()
        );
        assertTrue(result.browserValidation().runtimeViolations()
                .contains("browser_network_error"));
        verify(manager).startDevServer(any(App.class), eq(7L), options.capture());
        assertEquals(
                "http://127.0.0.1:19101/api",
                options.getValue().environmentOverrides().get("VITE_API_BASE_URL")
        );
        verify(manager).stopDevServer(11L);
    }

    @Test
    void readyCallbackMustFireWhileDevServerIsStillRunning() {
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenReturn(new DevServerStartResult(5180, true));
        AtomicReference<Boolean> stoppedWhenCallbackFired = new AtomicReference<>();
        AtomicInteger callbackInvocations = new AtomicInteger();

        DevServerValidationResult result = service.validate(
                DevServerValidationRequest.of("task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT)
                        .withExecutionFence(new GenerationExecutionFence("task-1", "owner-a", 1L))
                        .withReadyCallback(() -> {
                            callbackInvocations.incrementAndGet();
                            // 若此刻已经 stop，用户拿到的预览地址就是失效的，暂定预览毫无意义。
                            stoppedWhenCallbackFired.set(
                                    mockingDetails(manager).getInvocations().stream()
                                            .anyMatch(invocation ->
                                                    "stopDevServer".equals(invocation.getMethod().getName())));
                        }));

        assertTrue(result.isPassed());
        assertEquals(1, callbackInvocations.get());
        assertEquals(Boolean.FALSE, stoppedWhenCallbackFired.get(),
                "就绪回调必须在 Dev Server 停止之前触发");
        verify(manager).stopDevServer(11L);
    }

    @Test
    void taskScopedSessionMustSurviveValidationReturn() {
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenReturn(new DevServerStartResult(5180, true));

        DevServerValidationResult result = service.validate(
                DevServerValidationRequest.of("task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT)
                        .withExecutionFence(new GenerationExecutionFence("task-1", "owner-a", 1L))
                        .withTaskScopedOwnership());

        assertTrue(result.isPassed());
        // 暂定预览的全部意义在于验证返回后用户还点得开；这里一停，预览地址立刻失效。
        verify(manager, never()).stopDevServer(any());
        // 错误采集器仍必须注销，否则移交持有权会连带泄漏采集器。
        verify(manager).unregisterErrorCollector(11L, collectorReference.get());
    }

    @Test
    void taskScopedOwnershipMustNotBeInferredFromReadyCallback() {
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenReturn(new DevServerStartResult(5180, true));

        // 传了就绪回调但未声明任务作用域：持有权仍在调用方，必须照旧停。
        // 若实现改成「有回调就移交」，这条会失败 —— 那种反推会让持有权被一个体验参数悄悄改变。
        DevServerValidationResult result = service.validate(
                DevServerValidationRequest.of("task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT)
                        .withExecutionFence(new GenerationExecutionFence("task-1", "owner-a", 1L))
                        .withReadyCallback(() -> { }));

        assertTrue(result.isPassed());
        verify(manager).stopDevServer(11L);
    }

    @Test
    void taskScopedOwnershipMustNotStopReusedSession() {
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenReturn(new DevServerStartResult(5180, false));

        DevServerValidationResult result = service.validate(
                DevServerValidationRequest.of("task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT)
                        .withTaskScopedOwnership());

        assertTrue(result.isPassed());
        verify(manager, never()).stopDevServer(any());
    }

    @Test
    void readyCallbackFailureMustNotAffectValidationOutcome() {
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenReturn(new DevServerStartResult(5180, true));

        DevServerValidationResult result = service.validate(
                DevServerValidationRequest.of("task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT)
                        .withExecutionFence(new GenerationExecutionFence("task-1", "owner-a", 1L))
                        .withReadyCallback(() -> {
                            throw new IllegalStateException("暂定预览通知失败");
                        }));

        assertEquals(DevServerValidationResult.ValidationStatus.PASS, result.status());
        verify(manager).stopDevServer(11L);
    }

    @Test
    void readyCallbackMustNotFireWhenStartupFails() {
        when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                .thenThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "启动失败"));
        AtomicInteger callbackInvocations = new AtomicInteger();

        DevServerValidationResult result = service.validate(
                DevServerValidationRequest.of("task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT)
                        .withExecutionFence(new GenerationExecutionFence("task-1", "owner-a", 1L))
                        .withReadyCallback(callbackInvocations::incrementAndGet));

        assertFalse(result.isPassed());
        assertEquals(0, callbackInvocations.get(), "启动失败时不得通知用户可预览");
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
    void criticalOutputMustDrainItsDiagnosticBurstWithoutWaitingForTheFullWindow() throws Exception {
        properties.setValidationErrorCollectionWindow(Duration.ofSeconds(5));
        properties.setValidationCriticalErrorDrainWindow(Duration.ofMillis(300));
        properties.setValidationPollInterval(Duration.ofMillis(5));
        ExecutorService outputExecutor = Executors.newSingleThreadExecutor();
        try {
            when(manager.startDevServer(any(App.class), eq(7L), any(DevServerStartOptions.class)))
                    .thenAnswer(invocation -> {
                        collectorReference.get().feedLine(
                                "[vite] Internal server error: broken import");
                        outputExecutor.submit(() -> {
                            try {
                                Thread.sleep(30L);
                                collectorReference.get().feedLine(
                                        "Error: Cannot find module 'late-module'");
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                            }
                        });
                        return new DevServerStartResult(5180, false);
                    });
            long startedNanos = System.nanoTime();

            DevServerValidationResult result = service.validate(
                    "task-1", 11L, 7L, CodeGenTypeEnum.VUE_PROJECT
            );
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedNanos);

            assertEquals(DevServerValidationResult.ValidationStatus.FAILED, result.status());
            assertEquals(2, result.criticalErrorCount());
            assertTrue(elapsedMillis < 1_000L);
        } finally {
            outputExecutor.shutdownNow();
        }
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

    private BrowserRuntimeObservation browserObservationWithNetworkFailure(URI target) {
        return new BrowserRuntimeObservation(
                target,
                target,
                "Dashboard",
                "complete",
                120,
                1,
                true,
                1,
                12,
                1_600,
                900,
                false,
                "Dashboard content",
                "Dashboard",
                List.of(),
                List.of(),
                List.of(),
                BrowserRuntimeObservation.NetworkEvidence.captured(List.of(
                        new BrowserRuntimeObservation.NetworkFailure(
                                "http://127.0.0.1:19101/api/projects",
                                500,
                                "Internal Server Error"
                        )
                )),
                new BrowserRuntimeObservation.ScreenshotStats(true, 1_600, 900, 12, 180)
        );
    }
}
