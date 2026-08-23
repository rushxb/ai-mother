package com.rush.rushaicodemother.orchestration.readonly;

import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationSynchronousModelCallSupervisor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagedReadOnlyAnalysisModelTest {

    private static final GenerationSynchronousModelCallSupervisor MODEL_CALL_SUPERVISOR =
            new GenerationSynchronousModelCallSupervisor();

    @AfterAll
    static void closeModelCallSupervisor() {
        MODEL_CALL_SUPERVISOR.close();
    }

    @Test
    void cancellationDuringProviderCallMustReleaseReadOnlyWorkerPromptly() throws Exception {
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();
        properties.setModelCallTimeout(Duration.ofSeconds(20));
        GenerationExecutionContextService contexts = new GenerationExecutionContextService(properties);
        GenerationExecutionContext context = contexts.start("read-only-cancel-in-flight", 11L, 22L);
        ReadOnlyAnalysisServiceFactory serviceFactory = mock(ReadOnlyAnalysisServiceFactory.class);
        ReadOnlyAnalysisAiService aiService = mock(ReadOnlyAnalysisAiService.class);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(serviceFactory.create(any(Duration.class), any(Runnable.class), any(Runnable.class)))
                .thenReturn(aiService);
        when(aiService.analyze(any(), any(), any(), any())).thenAnswer(invocation -> {
            providerEntered.countDown();
            try {
                releaseProvider.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("provider_interrupted", interrupted);
            }
            return new ReadOnlyAnalysisResult(
                    "已完成审计", List.of(), List.of(), "本次请求未修改工作区");
        });
        GenerationPerformanceMonitorService performanceMonitorService =
                new GenerationPerformanceMonitorService();
        performanceMonitorService.startTask(
                "read-only-cancel-in-flight", 11L, 22L, "read_only", "vue_project");
        ManagedReadOnlyAnalysisModel model = new ManagedReadOnlyAnalysisModel(
                serviceFactory, contexts, performanceMonitorService, MODEL_CALL_SUPERVISOR);
        ReadOnlyAnalysisRequest request = new ReadOnlyAnalysisRequest(
                IntentOperationType.AUDIT,
                "审计鉴权链路",
                "src/auth.ts",
                List.of("src/auth.ts"));

        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            Future<Throwable> outcome = caller.submit(() -> {
                try {
                    model.analyze("read-only-cancel-in-flight", request);
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertTrue(providerEntered.await(2, TimeUnit.SECONDS));
            context.cancel("user_requested");

            Throwable failure;
            try {
                failure = outcome.get(1, TimeUnit.SECONDS);
            } finally {
                releaseProvider.countDown();
            }
            assertInstanceOf(GenerationExecutionCancelledException.class, failure);
        }
    }
}
