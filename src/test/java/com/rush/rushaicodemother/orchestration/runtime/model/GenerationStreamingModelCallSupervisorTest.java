package com.rush.rushaicodemother.orchestration.runtime.model;

import com.rush.rushaicodemother.ai.model.capacity.AiModelCapacityGuard;
import com.rush.rushaicodemother.ai.model.capacity.CapacityControlledStreamingChatModel;
import com.rush.rushaicodemother.ai.model.failover.AiModelCandidate;
import com.rush.rushaicodemother.ai.model.failover.FailoverStreamingChatModel;
import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.core.handler.GenerationCancellationAwareStreamingHandler;
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class GenerationStreamingModelCallSupervisorTest {

    private final GenerationStreamingModelCallSupervisor supervisor =
            new GenerationStreamingModelCallSupervisor();

    @AfterEach
    void tearDown() {
        supervisor.close();
    }

    @Test
    void providerWithoutCallbacksMustReachTheWallClockTimeout() throws Exception {
        RecordingHandler downstream = new RecordingHandler();

        supervisor.chat(
                new CapturingStreamingModel(),
                request(),
                context(Duration.ofMillis(60)),
                () -> false,
                ignored -> { },
                downstream);

        assertTrue(downstream.terminal.await(1, TimeUnit.SECONDS));
        assertInstanceOf(GenerationModelCallTimeoutException.class, downstream.error.get());
    }

    @Test
    void providerWithoutFirstSignalMustUseTheShorterFirstSignalDeadline() throws Exception {
        AiModelRuntimeProperties properties = new AiModelRuntimeProperties();
        properties.setFirstSignalTimeout(Duration.ofMillis(40));
        try (GenerationStreamingModelCallSupervisor boundedSupervisor =
                     new GenerationStreamingModelCallSupervisor(
                             new GenerationModelTimeoutPolicy(properties))) {
            RecordingHandler downstream = new RecordingHandler();

            boundedSupervisor.chat(
                    new CapturingStreamingModel(),
                    request(),
                    context(Duration.ofSeconds(1)),
                    () -> false,
                    ignored -> { },
                    downstream);

            assertTrue(downstream.terminal.await(1, TimeUnit.SECONDS));
            GenerationModelCallTimeoutException timeout = assertInstanceOf(
                    GenerationModelCallTimeoutException.class,
                    downstream.error.get()
            );
            assertEquals("first-signal", timeout.timeoutKind());
        }
    }

    @Test
    void firstSignalDeadlineMustStopApplyingAfterInitialModelActivity() throws Exception {
        AiModelRuntimeProperties properties = new AiModelRuntimeProperties();
        properties.setFirstSignalTimeout(Duration.ofMillis(40));
        try (GenerationStreamingModelCallSupervisor boundedSupervisor =
                     new GenerationStreamingModelCallSupervisor(
                             new GenerationModelTimeoutPolicy(properties))) {
            CapturingStreamingModel model = new CapturingStreamingModel();
            RecordingHandler downstream = new RecordingHandler();
            boundedSupervisor.chat(
                    model,
                    request(),
                    context(Duration.ofSeconds(1)),
                    () -> false,
                    ignored -> { },
                    downstream);

            model.handler().onPartialResponse("首个信号");
            Thread.sleep(100);

            assertFalse(downstream.terminal.await(10, TimeUnit.MILLISECONDS));
            model.handler().onCompleteResponse(ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from("完成"))
                    .build());
            assertTrue(downstream.terminal.await(1, TimeUnit.SECONDS));
            assertNull(downstream.error.get());
        }
    }

    @Test
    void callerProvidedStageTimeoutMustOverrideTheLargerModelLimit() throws Exception {
        RecordingHandler downstream = new RecordingHandler();

        supervisor.chat(
                new CapturingStreamingModel(),
                request(),
                context(Duration.ofSeconds(1)),
                Duration.ofMillis(50),
                () -> false,
                ignored -> { },
                downstream);

        assertTrue(downstream.terminal.await(1, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, downstream.error.get());
    }

    @Test
    void tokenActivityMustNotExtendTheAbsoluteDeadline() throws Exception {
        CapturingStreamingModel model = new CapturingStreamingModel();
        RecordingHandler downstream = new RecordingHandler();
        supervisor.chat(
                model,
                request(),
                context(Duration.ofMillis(120)),
                () -> false,
                ignored -> { },
                downstream);

        long deadline = System.nanoTime() + Duration.ofMillis(350).toNanos();
        while (System.nanoTime() < deadline && downstream.error.get() == null) {
            model.handler().onPartialResponse("token");
            Thread.sleep(20);
        }

        assertTrue(downstream.terminal.await(1, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, downstream.error.get());
        assertTrue(downstream.partials.get() > 0);
    }

    @Test
    void logicalCancellationMustWorkBeforeAProviderHandleExistsAndCancelLateHandles() throws Exception {
        CapturingStreamingModel model = new CapturingStreamingModel();
        RecordingHandler downstream = new RecordingHandler();
        AtomicReference<GenerationCancellationHandle> cancellation = new AtomicReference<>();
        supervisor.chat(
                model,
                request(),
                context(Duration.ofSeconds(1)),
                () -> false,
                cancellation::set,
                downstream);

        cancellation.get().cancel();
        assertTrue(downstream.terminal.await(1, TimeUnit.SECONDS));
        assertInstanceOf(GenerationExecutionCancelledException.class, downstream.error.get());

        TestStreamingHandle lateHandle = new TestStreamingHandle();
        model.handler().onPartialResponse(
                new PartialResponse("late"), new PartialResponseContext(lateHandle));

        assertTrue(lateHandle.cancelled.get());
    }

    @Test
    void blockedTimeoutCallbackMustNotDelayAnotherCallDeadline() throws Exception {
        CapturingStreamingModel firstModel = new CapturingStreamingModel();
        BlockingErrorHandler firstDownstream = new BlockingErrorHandler();
        CapturingStreamingModel secondModel = new CapturingStreamingModel();
        RecordingHandler secondDownstream = new RecordingHandler();

        supervisor.chat(
                firstModel,
                request(),
                context(Duration.ofMillis(40)),
                () -> false,
                ignored -> { },
                firstDownstream);
        supervisor.chat(
                secondModel,
                request(),
                context(Duration.ofMillis(120)),
                () -> false,
                ignored -> { },
                secondDownstream);

        try {
            assertTrue(firstDownstream.entered.await(1, TimeUnit.SECONDS));
            assertTrue(secondDownstream.terminal.await(1, TimeUnit.SECONDS),
                    "一个阻塞的超时回调不能拖延其他模型调用的截止时间");
            assertInstanceOf(TimeoutException.class, secondDownstream.error.get());

            firstModel.handler().onError(new IllegalStateException("late first error"));
            secondModel.handler().onError(new IllegalStateException("late second error"));
            assertEquals(1, firstDownstream.errorCalls.get());
            assertEquals(1, secondDownstream.terminalCalls.get());
        } finally {
            firstDownstream.release.countDown();
        }

        assertTrue(firstDownstream.exited.await(1, TimeUnit.SECONDS));
    }

    @Test
    void closeMustInterruptOutstandingTimeoutNotification() throws Exception {
        BlockingErrorHandler downstream = new BlockingErrorHandler();
        supervisor.chat(
                new CapturingStreamingModel(),
                request(),
                context(Duration.ofMillis(40)),
                () -> false,
                ignored -> { },
                downstream);

        assertTrue(downstream.entered.await(1, TimeUnit.SECONDS));
        supervisor.close();

        assertTrue(downstream.exited.await(1, TimeUnit.SECONDS));
        assertTrue(downstream.interrupted.get());
        assertTrue(downstream.callbackThread.get().isVirtual());
    }

    @Test
    void logicalCancellationMustReachProviderBeforeAStreamingHandleExists() throws Exception {
        AtomicInteger providerCancellations = new AtomicInteger();
        AtomicReference<GenerationCancellationHandle> logicalCancellation = new AtomicReference<>();
        RecordingHandler downstream = new RecordingHandler();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                if (handler instanceof GenerationCancellationAwareStreamingHandler awareHandler) {
                    awareHandler.registerCancellationHandle(providerCancellations::incrementAndGet);
                }
            }
        };

        supervisor.chat(
                model,
                request(),
                context(Duration.ofSeconds(1)),
                () -> false,
                logicalCancellation::set,
                downstream);
        logicalCancellation.get().cancel();

        assertTrue(downstream.terminal.await(1, TimeUnit.SECONDS));
        assertEquals(1, providerCancellations.get());
        assertInstanceOf(GenerationExecutionCancelledException.class, downstream.error.get());
    }

    @Test
    void cancellationDuringCapacityAcquisitionMustStopTheProductionWrapperStack() throws Exception {
        CountDownLatch acquisitionEntered = new CountDownLatch(1);
        CountDownLatch releaseAcquisition = new CountDownLatch(1);
        CountDownLatch chatReturned = new CountDownLatch(1);
        AtomicInteger leaseReleases = new AtomicInteger();
        AtomicInteger providerRequests = new AtomicInteger();
        AtomicReference<Throwable> chatFailure = new AtomicReference<>();
        AtomicReference<GenerationCancellationHandle> logicalCancellation = new AtomicReference<>();
        AiModelCapacityGuard guard = (provider, modelId, maxTokens, request) -> {
            acquisitionEntered.countDown();
            try {
                releaseAcquisition.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("容量获取线程被中断", interrupted);
            }
            return leaseReleases::incrementAndGet;
        };
        StreamingChatModel provider = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                providerRequests.incrementAndGet();
            }
        };
        StreamingChatModel capacityControlled = new CapacityControlledStreamingChatModel(
                "openai", "gpt-stream", 4096, provider, guard, Duration.ofSeconds(2));
        StreamingChatModel model = new FailoverStreamingChatModel(
                java.util.List.of(new AiModelCandidate<>(
                        "openai", "gpt-stream", capacityControlled)), null);
        RecordingHandler downstream = new RecordingHandler();

        Thread.ofVirtual().start(() -> {
            try {
                supervisor.chat(
                        model,
                        request(),
                        context(Duration.ofSeconds(2)),
                        () -> false,
                        logicalCancellation::set,
                        downstream);
            } catch (Throwable failure) {
                chatFailure.set(failure);
            } finally {
                chatReturned.countDown();
            }
        });

        try {
            assertTrue(acquisitionEntered.await(1, TimeUnit.SECONDS));
            logicalCancellation.get().cancel();
        } finally {
            releaseAcquisition.countDown();
        }

        assertTrue(chatReturned.await(1, TimeUnit.SECONDS));
        assertTrue(downstream.terminal.await(1, TimeUnit.SECONDS));
        assertNull(chatFailure.get());
        assertEquals(1, leaseReleases.get());
        assertEquals(0, providerRequests.get());
        assertInstanceOf(GenerationExecutionCancelledException.class, downstream.error.get());
    }

    private GenerationExecutionContext context(Duration modelTimeout) {
        Map<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 5);
        }
        GenerationExecutionLimits limits = new GenerationExecutionLimits(
                Duration.ofSeconds(2), modelTimeout, Duration.ofMillis(1), budgets);
        return new GenerationExecutionContext(
                "task-supervised", 1L, 2L, Instant.now(), limits, Clock.systemUTC());
    }

    private ChatRequest request() {
        return ChatRequest.builder()
                .messages(java.util.List.of(UserMessage.from("test")))
                .build();
    }

    private static final class CapturingStreamingModel implements StreamingChatModel {
        private final AtomicReference<StreamingChatResponseHandler> handler = new AtomicReference<>();

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            this.handler.set(handler);
        }

        private StreamingChatResponseHandler handler() {
            StreamingChatResponseHandler current = handler.get();
            if (current == null) {
                fail("streaming handler was not registered");
            }
            return current;
        }
    }

    private static final class RecordingHandler implements StreamingChatResponseHandler {
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final AtomicInteger partials = new AtomicInteger();
        private final AtomicInteger terminalCalls = new AtomicInteger();

        @Override
        public void onPartialResponse(String partialResponse) {
            partials.incrementAndGet();
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            terminalCalls.incrementAndGet();
            terminal.countDown();
        }

        @Override
        public void onError(Throwable error) {
            terminalCalls.incrementAndGet();
            this.error.compareAndSet(null, error);
            terminal.countDown();
        }
    }

    private static final class BlockingErrorHandler implements StreamingChatResponseHandler {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch exited = new CountDownLatch(1);
        private final AtomicInteger errorCalls = new AtomicInteger();
        private final AtomicBoolean interrupted = new AtomicBoolean();
        private final AtomicReference<Thread> callbackThread = new AtomicReference<>();

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            fail("阻塞回调不应收到完成事件");
        }

        @Override
        public void onError(Throwable error) {
            callbackThread.set(Thread.currentThread());
            errorCalls.incrementAndGet();
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                this.interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                exited.countDown();
            }
        }
    }

    private static final class TestStreamingHandle implements StreamingHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
