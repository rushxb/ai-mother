package com.rush.rushaicodemother.orchestration.runtime.model;

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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

        @Override
        public void onPartialResponse(String partialResponse) {
            partials.incrementAndGet();
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            terminal.countDown();
        }

        @Override
        public void onError(Throwable error) {
            this.error.compareAndSet(null, error);
            terminal.countDown();
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
