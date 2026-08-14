package com.rush.rushaicodemother.ai.model.transport;

import com.rush.rushaicodemother.infrastructure.security.AiModelOutboundDestinationPolicy;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCancellationScope;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelInvocationCancellationBridge;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import org.junit.jupiter.api.Test;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.rush.rushaicodemother.testsupport.AiModelOutboundSecurityTestFixtures.publicInternetPolicy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CancellableAiStreamingRequestExecutorTest {

    @Test
    void scopeCancellationMustInterruptRequestBeforeAnyResponseArrives() throws Exception {
        GenerationModelInvocationCancellationBridge bridge =
                new GenerationModelInvocationCancellationBridge();
        GenerationModelCancellationScope scope = new GenerationModelCancellationScope();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean interruptionObserved = new AtomicBoolean();

        try (CancellableAiStreamingRequestExecutor executor =
                     new CancellableAiStreamingRequestExecutor(bridge);
             GenerationModelInvocationCancellationBridge.ScopeBinding ignored =
                     bridge.activate(scope)) {
            executor.execute(() -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    interruptionObserved.set(true);
                    Thread.currentThread().interrupt();
                } finally {
                    interrupted.countDown();
                }
            });

            assertTrue(started.await(2, TimeUnit.SECONDS));
            scope.cancel();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            awaitNoActiveTasks(executor, Duration.ofSeconds(2));
            assertTrue(interruptionObserved.get());
            assertEquals(0, executor.activeTaskCount());
        }
    }

    @Test
    void cancellationMustInterruptTheSecuredApacheTransport() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean interruptionObserved = new AtomicBoolean();
        CloseableHttpClient apacheClient = mock(CloseableHttpClient.class);
        when(apacheClient.execute(any(HttpUriRequestBase.class))).thenAnswer(invocation -> {
            requestStarted.countDown();
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("阻塞的模型传输不应自行结束");
            } catch (InterruptedException expected) {
                interruptionObserved.set(true);
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("cancelled");
            } finally {
                interrupted.countDown();
            }
        });

        GenerationModelInvocationCancellationBridge bridge =
                new GenerationModelInvocationCancellationBridge();
        GenerationModelCancellationScope scope = new GenerationModelCancellationScope();
        try (CancellableAiStreamingRequestExecutor executor =
                     new CancellableAiStreamingRequestExecutor(bridge)) {
            AiModelOutboundDestinationPolicy policy =
                    publicInternetPolicy();
            SecuredAiHttpClient client = new SecuredAiHttpClient(
                    apacheClient,
                    policy,
                    policy.approveBaseUrl("https://8.8.8.8/v1"),
                    executor,
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(30)
            );
            HttpRequest request = HttpRequest.builder()
                .method(HttpMethod.GET)
                    .url("https://8.8.8.8/v1/stream")
                    .build();
            try (GenerationModelInvocationCancellationBridge.ScopeBinding ignored =
                         bridge.activate(scope)) {
                client.execute(request, new NoopSseListener());
            }

            assertTrue(requestStarted.await(2, TimeUnit.SECONDS));
            scope.cancel();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            awaitNoActiveTasks(executor, Duration.ofSeconds(3));

            assertEquals(0, executor.activeTaskCount());
            assertTrue(interruptionObserved.get());
        }
    }

    private void awaitNoActiveTasks(CancellableAiStreamingRequestExecutor executor,
                                    Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (executor.activeTaskCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static final class NoopSseListener implements ServerSentEventListener {

        @Override
        public void onOpen(SuccessfulHttpResponse response) {
        }

        @Override
        public void onEvent(ServerSentEvent event) {
        }

        @Override
        public void onClose() {
        }

        @Override
        public void onError(Throwable error) {
        }
    }
}
