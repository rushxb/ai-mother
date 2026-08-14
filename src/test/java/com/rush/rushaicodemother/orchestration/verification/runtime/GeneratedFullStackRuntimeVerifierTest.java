package com.rush.rushaicodemother.orchestration.verification.runtime;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeObservation;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeProbe;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationPolicy;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeVerifier;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import com.rush.rushaicodemother.service.devserver.DevServerStartOptions;
import com.rush.rushaicodemother.service.devserver.DevServerStartResult;
import com.rush.rushaicodemother.service.devserver.DevServerValidationRequest;
import com.rush.rushaicodemother.service.devserver.DevServerValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeneratedFullStackRuntimeVerifierTest {

    private GeneratedBackendRuntime backendRuntime;
    private DevServerManager devServerManager;
    private BrowserRuntimeProbe browserRuntimeProbe;
    private GenerationExecutionContextService executionContextService;
    private GeneratedFullStackRuntimeVerifier verifier;

    @BeforeEach
    void setUp() {
        backendRuntime = mock(GeneratedBackendRuntime.class);
        devServerManager = mock(DevServerManager.class);
        browserRuntimeProbe = mock(BrowserRuntimeProbe.class);
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setValidationErrorCollectionWindow(Duration.ofMillis(1));
        properties.setValidationCriticalErrorDrainWindow(Duration.ofMillis(1));
        properties.setValidationPollInterval(Duration.ofMillis(1));
        executionContextService = new GenerationExecutionContextService(
                new GenerationRuntimeProperties());
        DevServerValidationService frontendVerifier = new DevServerValidationService(
                devServerManager,
                properties,
                executionContextService,
                new BrowserRuntimeVerifier(browserRuntimeProbe)
        );
        verifier = new GeneratedFullStackRuntimeVerifier(backendRuntime, frontendVerifier);
    }

    @Test
    void backendMustRemainAliveWhileBrowserValidatesInjectedApiEndpoint() {
        Path backendPath = Path.of("target", "fullstack-runtime", "backend")
                .toAbsolutePath().normalize();
        AtomicBoolean backendAlive = new AtomicBoolean(true);
        AtomicBoolean backendClosed = new AtomicBoolean(false);
        when(backendRuntime.start(backendPath)).thenReturn(new GeneratedBackendRuntimeHandle(
                19_101,
                GeneratedBackendRuntimeObservation.passed(),
                backendAlive::get,
                () -> backendClosed.set(true)
        ));
        when(devServerManager.startDevServer(any(), eq(7L), any(DevServerStartOptions.class)))
                .thenReturn(new DevServerStartResult(5_180, true));
        URI frontendUri = URI.create("http://127.0.0.1:5180/");
        when(browserRuntimeProbe.inspect(frontendUri, Duration.ZERO)).thenAnswer(invocation -> {
            assertTrue(backendAlive.get());
            return healthyObservation(frontendUri);
        });
        ArgumentCaptor<DevServerStartOptions> startOptions =
                ArgumentCaptor.forClass(DevServerStartOptions.class);

        FullStackRuntimeValidationResult result = verifier.verify(
                backendPath,
                DevServerValidationRequest.of(
                        "fullstack-task", 101L, 7L, CodeGenTypeEnum.FULL_STACK_PROJECT),
                new BrowserRuntimeValidationPolicy(Duration.ZERO, false)
        );

        assertTrue(result.passed());
        assertTrue(result.frontend().browserValidation().runtimePassed());
        assertTrue(result.backend().processAlive());
        assertTrue(backendClosed.get());
        verify(devServerManager).startDevServer(any(), eq(7L), startOptions.capture());
        assertEquals(
                "http://127.0.0.1:19101/api",
                startOptions.getValue().environmentOverrides().get("VITE_API_BASE_URL")
        );
        verify(devServerManager).stopDevServer(101L);
    }

    @Test
    void taskCancellationMustPropagateAndStillCloseBackendRuntime() {
        Path backendPath = Path.of("target", "fullstack-cancel", "backend")
                .toAbsolutePath().normalize();
        AtomicBoolean backendClosed = new AtomicBoolean(false);
        when(backendRuntime.start(backendPath)).thenReturn(new GeneratedBackendRuntimeHandle(
                19_101,
                GeneratedBackendRuntimeObservation.passed(),
                () -> true,
                () -> backendClosed.set(true)
        ));
        executionContextService.start("cancelled-fullstack", 102L, 7L);
        executionContextService.cancelByTaskId(
                "cancelled-fullstack", "user requested cancellation");

        assertThrows(GenerationExecutionCancelledException.class, () -> verifier.verify(
                backendPath,
                DevServerValidationRequest.of(
                        "cancelled-fullstack",
                        102L,
                        7L,
                        CodeGenTypeEnum.FULL_STACK_PROJECT
                ),
                new BrowserRuntimeValidationPolicy(Duration.ZERO, false)
        ));

        assertTrue(backendClosed.get());
    }

    private BrowserRuntimeObservation healthyObservation(URI target) {
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
                BrowserRuntimeObservation.NetworkEvidence.captured(List.of()),
                new BrowserRuntimeObservation.ScreenshotStats(true, 1_600, 900, 12, 180)
        );
    }
}
