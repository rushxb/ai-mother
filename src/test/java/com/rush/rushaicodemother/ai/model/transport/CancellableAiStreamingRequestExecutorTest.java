package com.rush.rushaicodemother.ai.model.transport;

import com.sun.net.httpserver.HttpServer;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCancellationScope;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelInvocationCancellationBridge;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.spring.restclient.SpringRestClient;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void cancellationMustCloseTheRealSpringRestClientSseConnection() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch clientDisconnected = new CountDownLatch(1);
        AtomicBoolean stopServerLoop = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stream", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            requestStarted.countDown();
            byte[] heartbeat = ": keepalive\n\n".getBytes(StandardCharsets.UTF_8);
            try (var output = exchange.getResponseBody()) {
                while (!stopServerLoop.get()) {
                    output.write(heartbeat);
                    output.flush();
                    Thread.sleep(20);
                }
            } catch (IOException disconnected) {
                clientDisconnected.countDown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        GenerationModelInvocationCancellationBridge bridge =
                new GenerationModelInvocationCancellationBridge();
        GenerationModelCancellationScope scope = new GenerationModelCancellationScope();
        try (CancellableAiStreamingRequestExecutor executor =
                     new CancellableAiStreamingRequestExecutor(bridge)) {
            SpringRestClient client = SpringRestClient.builder()
                    .streamingRequestExecutor(executor)
                    .createDefaultStreamingRequestExecutor(false)
                    .connectTimeout(Duration.ofSeconds(2))
                    .readTimeout(Duration.ofSeconds(30))
                    .build();
            HttpRequest request = HttpRequest.builder()
                    .method(HttpMethod.GET)
                    .url("http://127.0.0.1:" + server.getAddress().getPort() + "/stream")
                    .build();
            try (GenerationModelInvocationCancellationBridge.ScopeBinding ignored =
                         bridge.activate(scope)) {
                client.execute(request, new NoopSseListener());
            }

            assertTrue(requestStarted.await(2, TimeUnit.SECONDS));
            scope.cancel();
            awaitNoActiveTasks(executor, Duration.ofSeconds(3));

            assertEquals(0, executor.activeTaskCount());
            assertTrue(clientDisconnected.await(3, TimeUnit.SECONDS));
        } finally {
            stopServerLoop.set(true);
            server.stop(0);
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
