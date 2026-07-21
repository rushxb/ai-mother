package com.rush.rushaicodemother.orchestration.runtime.model;

import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Supervises one logical streaming-model turn with absolute and inactivity deadlines.
 *
 * <p>The returned cancellation handle is registered before the provider is invoked. Cancellation
 * therefore terminates the logical call even when a provider has not exposed a
 * {@link StreamingHandle} yet. If a late handle arrives after termination, it is cancelled
 * immediately.</p>
 */
@Slf4j
@Component
public class GenerationStreamingModelCallSupervisor implements AutoCloseable {

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    public GenerationStreamingModelCallSupervisor() {
        this(createScheduler());
    }

    GenerationStreamingModelCallSupervisor(ScheduledExecutorService scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public void chat(StreamingChatModel model,
                     ChatRequest request,
                     GenerationExecutionContext executionContext,
                     BooleanSupplier cancelChecker,
                     Consumer<GenerationCancellationHandle> cancellationHandleConsumer,
                     StreamingChatResponseHandler downstream) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(executionContext, "executionContext");
        Objects.requireNonNull(downstream, "downstream");
        if (shuttingDown.get()) {
            throw new IllegalStateException("model call supervisor is shutting down");
        }
        Duration timeout = executionContext.clampTimeout(
                executionContext.limits().modelCallTimeout());
        SupervisedCall call = new SupervisedCall(
                model,
                request,
                executionContext,
                cancelChecker == null ? () -> false : cancelChecker,
                downstream,
                timeout
        );
        Consumer<GenerationCancellationHandle> safeConsumer = cancellationHandleConsumer == null
                ? ignored -> { }
                : cancellationHandleConsumer;
        safeConsumer.accept(call);
        call.start();
    }

    @PreDestroy
    @Override
    public void close() {
        if (shuttingDown.compareAndSet(false, true)) {
            scheduler.shutdownNow();
        }
    }

    private static ScheduledExecutorService createScheduler() {
        ThreadFactory threadFactory = runnable -> Thread.ofPlatform()
                .name("generation-model-call-supervisor")
                .daemon(true)
                .unstarted(runnable);
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private final class SupervisedCall implements GenerationCancellationHandle {

        private final StreamingChatModel model;
        private final ChatRequest request;
        private final GenerationExecutionContext executionContext;
        private final BooleanSupplier cancelChecker;
        private final StreamingChatResponseHandler downstream;
        private final Duration timeout;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicReference<StreamingHandle> activeHandle = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> wallClockTimer = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> inactivityTimer = new AtomicReference<>();

        private SupervisedCall(StreamingChatModel model,
                               ChatRequest request,
                               GenerationExecutionContext executionContext,
                               BooleanSupplier cancelChecker,
                               StreamingChatResponseHandler downstream,
                               Duration timeout) {
            this.model = model;
            this.request = request;
            this.executionContext = executionContext;
            this.cancelChecker = cancelChecker;
            this.downstream = downstream;
            this.timeout = timeout;
        }

        private void start() {
            if (cancelled()) {
                cancel();
                return;
            }
            armWallClockTimer();
            armInactivityTimer();
            try {
                model.chat(request, forwardingHandler());
            } catch (RuntimeException synchronousFailure) {
                fail(synchronousFailure, true);
            }
        }

        @Override
        public void cancel() {
            String reason = executionContext.cancellationReason();
            fail(new GenerationExecutionCancelledException(
                    reason == null || reason.isBlank() ? "cancelled" : reason), true);
        }

        private StreamingChatResponseHandler forwardingHandler() {
            return new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    forward(null, () -> downstream.onPartialResponse(partialResponse));
                }

                @Override
                public void onPartialResponse(PartialResponse partialResponse,
                                              PartialResponseContext context) {
                    forward(context == null ? null : context.streamingHandle(),
                            () -> downstream.onPartialResponse(partialResponse, context));
                }

                @Override
                public void onPartialThinking(PartialThinking partialThinking) {
                    forward(null, () -> downstream.onPartialThinking(partialThinking));
                }

                @Override
                public void onPartialThinking(PartialThinking partialThinking,
                                              PartialThinkingContext context) {
                    forward(context == null ? null : context.streamingHandle(),
                            () -> downstream.onPartialThinking(partialThinking, context));
                }

                @Override
                public void onPartialToolCall(PartialToolCall partialToolCall) {
                    forward(null, () -> downstream.onPartialToolCall(partialToolCall));
                }

                @Override
                public void onPartialToolCall(PartialToolCall partialToolCall,
                                              PartialToolCallContext context) {
                    forward(context == null ? null : context.streamingHandle(),
                            () -> downstream.onPartialToolCall(partialToolCall, context));
                }

                @Override
                public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                    forward(null, () -> downstream.onCompleteToolCall(completeToolCall));
                }

                @Override
                public void onUnmappedRawEvent(Object event) {
                    forward(null, () -> downstream.onUnmappedRawEvent(event));
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    complete(() -> downstream.onCompleteResponse(completeResponse));
                }

                @Override
                public void onError(Throwable error) {
                    fail(error == null ? new IllegalStateException("model stream failed without an error") : error,
                            false);
                }
            };
        }

        private void forward(StreamingHandle handle, Runnable callback) {
            registerHandle(handle);
            if (terminal.get()) {
                return;
            }
            if (cancelled()) {
                cancel();
                return;
            }
            if (executionContext.isDeadlineExceeded()) {
                timeout("wall-clock");
                return;
            }
            armInactivityTimer();
            try {
                callback.run();
            } catch (RuntimeException callbackFailure) {
                fail(callbackFailure, true);
            }
        }

        private void complete(Runnable callback) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            cancelTimers();
            try {
                callback.run();
            } catch (RuntimeException callbackFailure) {
                log.warn("Model completion callback failed, taskId: {}",
                        executionContext.taskId(), LogExceptionSanitizer.sanitize(callbackFailure));
            }
        }

        private void fail(Throwable failure, boolean cancelProvider) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            cancelTimers();
            if (cancelProvider) {
                cancelActiveHandle();
            }
            try {
                downstream.onError(failure);
            } catch (RuntimeException callbackFailure) {
                failure.addSuppressed(callbackFailure);
                log.warn("Model error callback failed, taskId: {}",
                        executionContext.taskId(), LogExceptionSanitizer.sanitize(failure));
            }
        }

        private void timeout(String kind) {
            Throwable failure = executionContext.isDeadlineExceeded()
                    ? new GenerationDeadlineExceededException(executionContext.taskId())
                    : new TimeoutException("model call " + kind + " timeout");
            fail(failure, true);
        }

        private void registerHandle(StreamingHandle handle) {
            if (handle == null) {
                return;
            }
            if (terminal.get()) {
                cancelHandle(handle);
                return;
            }
            StreamingHandle previous = activeHandle.getAndSet(handle);
            if (terminal.get() && activeHandle.compareAndSet(handle, null)) {
                cancelHandle(handle);
            } else if (previous != null && previous != handle) {
                cancelHandle(previous);
            }
        }

        private void armWallClockTimer() {
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> timeout("wall-clock"), timeout.toNanos(), TimeUnit.NANOSECONDS);
            replaceTimer(wallClockTimer, future);
        }

        private void armInactivityTimer() {
            if (terminal.get()) {
                return;
            }
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> timeout("inactivity"), timeout.toNanos(), TimeUnit.NANOSECONDS);
            replaceTimer(inactivityTimer, future);
        }

        private void replaceTimer(AtomicReference<ScheduledFuture<?>> target,
                                  ScheduledFuture<?> replacement) {
            ScheduledFuture<?> previous = target.getAndSet(replacement);
            if (previous != null) {
                previous.cancel(false);
            }
            if (terminal.get() && target.compareAndSet(replacement, null)) {
                replacement.cancel(false);
            }
        }

        private void cancelTimers() {
            cancelTimer(wallClockTimer);
            cancelTimer(inactivityTimer);
        }

        private void cancelTimer(AtomicReference<ScheduledFuture<?>> target) {
            ScheduledFuture<?> future = target.getAndSet(null);
            if (future != null) {
                future.cancel(false);
            }
        }

        private boolean cancelled() {
            return executionContext.isCancelled() || cancelChecker.getAsBoolean();
        }

        private void cancelActiveHandle() {
            cancelHandle(activeHandle.getAndSet(null));
        }

        private void cancelHandle(StreamingHandle handle) {
            if (handle == null || handle.isCancelled()) {
                return;
            }
            try {
                handle.cancel();
            } catch (RuntimeException cancellationFailure) {
                log.warn("Failed to cancel supervised AI stream, taskId: {}",
                        executionContext.taskId(), LogExceptionSanitizer.sanitize(cancellationFailure));
            }
        }
    }
}
