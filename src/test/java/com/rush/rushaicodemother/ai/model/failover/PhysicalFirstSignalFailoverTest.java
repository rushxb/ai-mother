package com.rush.rushaicodemother.ai.model.failover;

import com.rush.rushaicodemother.ai.model.capacity.AiModelCapacityGuard;
import com.rush.rushaicodemother.ai.model.capacity.CapacityControlledStreamingChatModel;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import com.rush.rushaicodemother.monitor.AiModelTimeoutMonitor;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCallTimeoutException;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCancellationScope;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelInvocationCancellationBridge;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelTimeoutScheduler;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PhysicalFirstSignalFailoverTest {

    @Test
    void silentProviderMustBeCancelledBeforeFailingOverToNextCandidate() {
        GenerationModelInvocationCancellationBridge bridge =
                new GenerationModelInvocationCancellationBridge();
        GenerationModelCancellationScope scope = new GenerationModelCancellationScope();
        ManualTimeoutScheduler scheduler = new ManualTimeoutScheduler();
        AiModelTimeoutMonitor timeoutMonitor = mock(AiModelTimeoutMonitor.class);
        ConcurrentHashMap<String, AtomicInteger> leaseReleases = new ConcurrentHashMap<>();
        AiModelCapacityGuard capacityGuard = (provider, modelId, maxTokens, request) ->
                leaseReleases.computeIfAbsent(provider, ignored -> new AtomicInteger())::incrementAndGet;
        AtomicInteger firstTransportCancellations = new AtomicInteger();
        AtomicInteger secondStarts = new AtomicInteger();

        StreamingChatModel first = provider(handler -> {
            bridge.registerTransportCancellation(firstTransportCancellations::incrementAndGet);
        });
        StreamingChatModel second = provider(handler -> {
            secondStarts.incrementAndGet();
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("完成"))
                    .build());
        });
        Duration providerTimeout = Duration.ofSeconds(10);
        Duration firstSignalTimeout = Duration.ofSeconds(2);
        StreamingChatModel firstControlled = controlled(
                "provider-a", "model-a", first, capacityGuard, providerTimeout,
                firstSignalTimeout, bridge, scheduler, timeoutMonitor);
        StreamingChatModel secondControlled = controlled(
                "provider-b", "model-b", second, capacityGuard, providerTimeout,
                firstSignalTimeout, bridge, scheduler, timeoutMonitor);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        FailoverStreamingChatModel failover = new FailoverStreamingChatModel(
                List.of(
                        new AiModelCandidate<>("provider-a", "model-a", firstControlled),
                        new AiModelCandidate<>("provider-b", "model-b", secondControlled)
                ),
                new AiModelMetricsCollector(meterRegistry),
                bridge
        );
        AtomicReference<ChatResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("生成页面"))
                .build();

        try (GenerationModelInvocationCancellationBridge.ScopeBinding ignored =
                     bridge.activate(scope)) {
            failover.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    response.set(completeResponse);
                }

                @Override
                public void onError(Throwable error) {
                    failure.set(error);
                }
            });
        }

        assertEquals(1, scheduler.pendingTaskCount());
        assertEquals(0, secondStarts.get());
        scheduler.fireFirstPending();

        assertEquals(1, firstTransportCancellations.get());
        assertEquals(1, leaseReleases.get("provider-a").get());
        assertEquals(1, leaseReleases.get("provider-b").get());
        assertEquals(1, secondStarts.get());
        assertTrue(response.get() != null && "完成".equals(response.get().aiMessage().text()));
        assertNull(failure.get());
        assertFalse(scope.isCancelled());
        verify(timeoutMonitor).record(
                org.mockito.ArgumentMatchers.eq("provider-a"),
                org.mockito.ArgumentMatchers.eq("model-a"),
                org.mockito.ArgumentMatchers.argThat(timeout ->
                        timeout instanceof GenerationModelCallTimeoutException
                                && "first-signal".equals(timeout.timeoutKind()))
        );
        assertEquals(1.0, meterRegistry.find("ai_model_failovers_total")
                .tag("from_provider", "provider-a")
                .tag("to_provider", "provider-b")
                .tag("error_category", "model_timeout")
                .counter()
                .count());
    }

    private StreamingChatModel controlled(String provider,
                                           String model,
                                           StreamingChatModel delegate,
                                           AiModelCapacityGuard capacityGuard,
                                           Duration upstreamTimeout,
                                           Duration firstSignalTimeout,
                                           GenerationModelInvocationCancellationBridge bridge,
                                           GenerationModelTimeoutScheduler scheduler,
                                           AiModelTimeoutMonitor timeoutMonitor) {
        return new CapacityControlledStreamingChatModel(
                provider,
                model,
                1024,
                delegate,
                capacityGuard,
                upstreamTimeout,
                firstSignalTimeout,
                bridge,
                scheduler,
                timeoutMonitor
        );
    }

    private StreamingChatModel provider(
            java.util.function.Consumer<StreamingChatResponseHandler> invocation) {
        return new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                invocation.accept(handler);
            }
        };
    }

    private static final class ManualTimeoutScheduler implements GenerationModelTimeoutScheduler {

        private final List<ScheduledTask> tasks = new ArrayList<>();

        @Override
        public synchronized com.rush.rushaicodemother.core.handler.GenerationCancellationHandle schedule(
                Duration delay,
                Runnable timeoutAction) {
            ScheduledTask task = new ScheduledTask(timeoutAction);
            tasks.add(task);
            return task::cancel;
        }

        private synchronized int pendingTaskCount() {
            return (int) tasks.stream().filter(ScheduledTask::pending).count();
        }

        private void fireFirstPending() {
            ScheduledTask task;
            synchronized (this) {
                task = tasks.stream().filter(ScheduledTask::pending).findFirst().orElseThrow();
            }
            task.fire();
        }
    }

    private static final class ScheduledTask {

        private final Runnable action;
        private boolean cancelled;
        private boolean fired;

        private ScheduledTask(Runnable action) {
            this.action = action;
        }

        private synchronized boolean pending() {
            return !cancelled && !fired;
        }

        private synchronized void cancel() {
            cancelled = true;
        }

        private void fire() {
            synchronized (this) {
                if (!pending()) {
                    return;
                }
                fired = true;
            }
            action.run();
        }
    }
}
