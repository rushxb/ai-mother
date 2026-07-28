package com.rush.rushaicodemother.orchestration.runtime.model;

import com.rush.rushaicodemother.core.handler.GenerationCancellationAwareStreamingHandler;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 以首信号、流空闲和整回合墙钟三层时限监督一次流式模型回合。
 *
 * <p>调用供应商前先注册逻辑取消句柄，因此即使供应商尚未暴露 {@link StreamingHandle}，
 * 任务取消和超时也能终止本次逻辑调用；迟到句柄会被立即取消。</p>
 */
@Slf4j
@Component
public class GenerationStreamingModelCallSupervisor implements AutoCloseable {

    private final ScheduledExecutorService scheduler;
    private final ExecutorService timeoutTerminalExecutor;
    private final GenerationModelTimeoutPolicy timeoutPolicy;
    private final GenerationModelInvocationCancellationBridge cancellationBridge;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    public GenerationStreamingModelCallSupervisor() {
        this(createScheduler(), createTimeoutTerminalExecutor(),
                GenerationModelTimeoutPolicy.defaults(),
                new GenerationModelInvocationCancellationBridge());
    }

    public GenerationStreamingModelCallSupervisor(GenerationModelTimeoutPolicy timeoutPolicy) {
        this(createScheduler(), createTimeoutTerminalExecutor(), timeoutPolicy,
                new GenerationModelInvocationCancellationBridge());
    }

    @Autowired
    public GenerationStreamingModelCallSupervisor(
            GenerationModelTimeoutPolicy timeoutPolicy,
            GenerationModelInvocationCancellationBridge cancellationBridge) {
        this(createScheduler(), createTimeoutTerminalExecutor(), timeoutPolicy, cancellationBridge);
    }

    GenerationStreamingModelCallSupervisor(ScheduledExecutorService scheduler) {
        this(scheduler, createTimeoutTerminalExecutor(), GenerationModelTimeoutPolicy.defaults(),
                new GenerationModelInvocationCancellationBridge());
    }

    GenerationStreamingModelCallSupervisor(ScheduledExecutorService scheduler,
                                            ExecutorService timeoutTerminalExecutor) {
        this(scheduler, timeoutTerminalExecutor, GenerationModelTimeoutPolicy.defaults(),
                new GenerationModelInvocationCancellationBridge());
    }

    GenerationStreamingModelCallSupervisor(ScheduledExecutorService scheduler,
                                            ExecutorService timeoutTerminalExecutor,
                                            GenerationModelTimeoutPolicy timeoutPolicy) {
        this(scheduler, timeoutTerminalExecutor, timeoutPolicy,
                new GenerationModelInvocationCancellationBridge());
    }

    GenerationStreamingModelCallSupervisor(
            ScheduledExecutorService scheduler,
            ExecutorService timeoutTerminalExecutor,
            GenerationModelTimeoutPolicy timeoutPolicy,
            GenerationModelInvocationCancellationBridge cancellationBridge) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.timeoutTerminalExecutor = Objects.requireNonNull(
                timeoutTerminalExecutor, "timeoutTerminalExecutor");
        this.timeoutPolicy = Objects.requireNonNull(timeoutPolicy, "模型超时策略不能为空");
        this.cancellationBridge = Objects.requireNonNull(
                cancellationBridge, "模型取消桥不能为空");
    }

    /**
 * 处理对话。
 *
 * @param model 模型
 * @param request 请求参数
 * @param executionContext 执行上下文
 * @param cancelChecker {@code cancelChecker} 对应的调用参数
 * @param cancellationHandleConsumer 对应阶段使用的回调函数
 * @param downstream {@code downstream} 对应的调用参数
 */
    public void chat(StreamingChatModel model,
                     ChatRequest request,
                     GenerationExecutionContext executionContext,
                     BooleanSupplier cancelChecker,
                     Consumer<GenerationCancellationHandle> cancellationHandleConsumer,
                     StreamingChatResponseHandler downstream) {
        Objects.requireNonNull(executionContext, "executionContext");
        chat(
                model,
                request,
                executionContext,
                executionContext.limits().modelCallTimeout(),
                cancelChecker,
                cancellationHandleConsumer,
                downstream
        );
    }

    /** 使用调用方已经按阶段完成窗口收紧的模型超时。 */
    public void chat(StreamingChatModel model,
                     ChatRequest request,
                     GenerationExecutionContext executionContext,
                     Duration requestedTimeout,
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
        Duration timeout = executionContext.clampTimeout(requestedTimeout);
        SupervisedCall call = new SupervisedCall(
                model,
                request,
                executionContext,
                cancelChecker == null ? () -> false : cancelChecker,
                downstream,
                timeout,
                timeoutPolicy.firstSignalTimeout(timeout)
        );
        Consumer<GenerationCancellationHandle> safeConsumer = cancellationHandleConsumer == null
                ? ignored -> { }
                : cancellationHandleConsumer;
        safeConsumer.accept(call);
        call.start();
    }

    /** 关闭生成{@code Streaming}模型调用{@code Supervisor}并释放资源。 */
    @PreDestroy
    @Override
    public void close() {
        if (shuttingDown.compareAndSet(false, true)) {
            scheduler.shutdownNow();
            timeoutTerminalExecutor.shutdownNow();
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

    private static ExecutorService createTimeoutTerminalExecutor() {
        ThreadFactory threadFactory = Thread.ofVirtual()
                .name("generation-model-timeout-terminal-", 0)
                .factory();
        return Executors.newThreadPerTaskExecutor(threadFactory);
    }

    private final class SupervisedCall implements GenerationCancellationHandle {

        private final StreamingChatModel model;
        private final ChatRequest request;
        private final GenerationExecutionContext executionContext;
        private final BooleanSupplier cancelChecker;
        private final StreamingChatResponseHandler downstream;
        private final Duration timeout;
        private final Duration firstSignalTimeout;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicReference<GenerationCancellationHandle> activeCancellationHandle =
                new AtomicReference<>();
        private final GenerationModelCancellationScope transportCancellationScope =
                new GenerationModelCancellationScope();
        private final AtomicReference<StreamingHandle> activeHandle = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> wallClockTimer = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> inactivityTimer = new AtomicReference<>();

        private SupervisedCall(StreamingChatModel model,
                               ChatRequest request,
                               GenerationExecutionContext executionContext,
                               BooleanSupplier cancelChecker,
                               StreamingChatResponseHandler downstream,
                               Duration timeout,
                               Duration firstSignalTimeout) {
            this.model = model;
            this.request = request;
            this.executionContext = executionContext;
            this.cancelChecker = cancelChecker;
            this.downstream = downstream;
            this.timeout = timeout;
            this.firstSignalTimeout = firstSignalTimeout;
        }

        /** 启动{@code Supervised}调用。 */
        private void start() {
            if (cancelled()) {
                cancel();
                return;
            }
            armWallClockTimer();
            armProgressTimer(firstSignalTimeout, "first-signal");
            try (GenerationModelInvocationCancellationBridge.ScopeBinding ignored =
                         cancellationBridge.activate(transportCancellationScope)) {
                model.chat(request, forwardingHandler());
            } catch (RuntimeException synchronousFailure) {
                fail(synchronousFailure, true);
            }
        }

        /** 取消{@code Supervised}调用。 */
        @Override
        public void cancel() {
            String reason = executionContext.cancellationReason();
            fail(new GenerationExecutionCancelledException(
                    reason == null || reason.isBlank() ? "cancelled" : reason), true);
        }

        /** 转发{@code ing}处理器。 */
        private StreamingChatResponseHandler forwardingHandler() {
            return new GenerationCancellationAwareStreamingHandler() {
                @Override
                public void registerCancellationHandle(GenerationCancellationHandle cancellationHandle) {
                    registerUpstreamCancellation(cancellationHandle);
                }

                /**
 * 响应部分响应事件。
 *
 * @param partialResponse 部分响应
 */
                @Override
                public void onPartialResponse(String partialResponse) {
                    forward(null, () -> downstream.onPartialResponse(partialResponse));
                }

                /**
 * 响应部分响应事件。
 *
 * @param partialResponse 部分响应
 * @param context 执行上下文
 */
                @Override
                public void onPartialResponse(PartialResponse partialResponse,
                                              PartialResponseContext context) {
                    forward(context == null ? null : context.streamingHandle(),
                            () -> downstream.onPartialResponse(partialResponse, context));
                }

                /**
 * 响应部分{@code Thinking}事件。
 *
 * @param partialThinking {@code partialThinking} 对应的调用参数
 */
                @Override
                public void onPartialThinking(PartialThinking partialThinking) {
                    forward(null, () -> downstream.onPartialThinking(partialThinking));
                }

                /**
 * 响应部分{@code Thinking}事件。
 *
 * @param partialThinking {@code partialThinking} 对应的调用参数
 * @param context 执行上下文
 */
                @Override
                public void onPartialThinking(PartialThinking partialThinking,
                                              PartialThinkingContext context) {
                    forward(context == null ? null : context.streamingHandle(),
                            () -> downstream.onPartialThinking(partialThinking, context));
                }

                /**
 * 响应部分工具调用事件。
 *
 * @param partialToolCall 部分工具调用
 */
                @Override
                public void onPartialToolCall(PartialToolCall partialToolCall) {
                    forward(null, () -> downstream.onPartialToolCall(partialToolCall));
                }

                /**
 * 响应部分工具调用事件。
 *
 * @param partialToolCall 部分工具调用
 * @param context 执行上下文
 */
                @Override
                public void onPartialToolCall(PartialToolCall partialToolCall,
                                              PartialToolCallContext context) {
                    forward(context == null ? null : context.streamingHandle(),
                            () -> downstream.onPartialToolCall(partialToolCall, context));
                }

                /**
 * 响应{@code Complete}工具调用事件。
 *
 * @param completeToolCall {@code completeToolCall} 对应的调用参数
 */
                @Override
                public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                    forward(null, () -> downstream.onCompleteToolCall(completeToolCall));
                }

                /**
 * 响应{@code Unmapped}原始事件事件。
 *
 * @param event 待处理的领域事件
 */
                @Override
                public void onUnmappedRawEvent(Object event) {
                    forward(null, () -> downstream.onUnmappedRawEvent(event));
                }

                /**
 * 响应{@code Complete}响应事件。
 *
 * @param completeResponse {@code completeResponse} 对应的调用参数
 */
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

        /** 转发{@code Supervised}调用。 */
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
            armProgressTimer(timeout, "inactivity");
            try {
                callback.run();
            } catch (RuntimeException callbackFailure) {
                fail(callbackFailure, true);
            }
        }

        /** 完成{@code Supervised}调用并持久化终态。 */
        private void complete(Runnable callback) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            cancelTimers();
            clearActiveHandles();
            try {
                callback.run();
            } catch (RuntimeException callbackFailure) {
                log.warn("Model completion callback failed, taskId: {}",
                        executionContext.taskId(), LogExceptionSanitizer.sanitize(callbackFailure));
            }
        }

        private void fail(Throwable failure, boolean cancelProvider) {
            if (!claimTerminal()) {
                return;
            }
            notifyFailure(failure, cancelProvider);
        }

        /** 将{@code From}{@code Watchdog}标记为失败并记录原因。 */
        private void failFromWatchdog(Throwable failure) {
            if (!claimTerminal()) {
                return;
            }
            try {
                timeoutTerminalExecutor.execute(() -> notifyFailure(failure, true));
            } catch (RejectedExecutionException shutdownRace) {
                failure.addSuppressed(shutdownRace);
                notifyFailure(failure, true);
            }
        }

        private boolean claimTerminal() {
            if (!terminal.compareAndSet(false, true)) {
                return false;
            }
            cancelTimers();
            return true;
        }

        /** 处理通知失败。 */
        private void notifyFailure(Throwable failure, boolean cancelProvider) {
            if (cancelProvider) {
                cancelActiveHandles();
            } else {
                clearActiveHandles();
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
            fail(timeoutFailure(kind), true);
        }

        private void timeoutFromWatchdog(String kind) {
            failFromWatchdog(timeoutFailure(kind));
        }

        private Throwable timeoutFailure(String kind) {
            Throwable failure = executionContext.isDeadlineExceeded()
                    ? new GenerationDeadlineExceededException(executionContext.taskId())
                    : new GenerationModelCallTimeoutException(kind);
            return failure;
        }

        /** 注册句柄。 */
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

        /** 注册{@code Upstream}{@code Cancellation}。 */
        private void registerUpstreamCancellation(GenerationCancellationHandle cancellationHandle) {
            if (cancellationHandle == null) {
                throw new IllegalArgumentException("上游取消句柄不能为空");
            }
            if (terminal.get()) {
                cancelCancellationHandle(cancellationHandle);
                return;
            }
            GenerationCancellationHandle previous =
                    activeCancellationHandle.getAndSet(cancellationHandle);
            if (terminal.get()
                    && activeCancellationHandle.compareAndSet(cancellationHandle, null)) {
                cancelCancellationHandle(cancellationHandle);
            } else if (previous != null && previous != cancellationHandle) {
                cancelCancellationHandle(previous);
            }
        }

        private void armWallClockTimer() {
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> timeoutFromWatchdog("wall-clock"), timeout.toNanos(), TimeUnit.NANOSECONDS);
            replaceTimer(wallClockTimer, future);
        }

        private void armProgressTimer(Duration delay, String timeoutKind) {
            if (terminal.get()) {
                return;
            }
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> timeoutFromWatchdog(timeoutKind), delay.toNanos(), TimeUnit.NANOSECONDS);
            replaceTimer(inactivityTimer, future);
        }

        /** 处理{@code replace}{@code Timer}。 */
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

        private void cancelActiveHandles() {
            cancelCancellationHandle(activeCancellationHandle.getAndSet(null));
            cancelHandle(activeHandle.getAndSet(null));
            transportCancellationScope.cancel();
        }

        private void clearActiveHandles() {
            activeCancellationHandle.set(null);
            activeHandle.set(null);
            transportCancellationScope.complete();
        }

        /** 取消{@code Cancellation}句柄。 */
        private void cancelCancellationHandle(GenerationCancellationHandle cancellationHandle) {
            if (cancellationHandle == null) {
                return;
            }
            try {
                cancellationHandle.cancel();
            } catch (RuntimeException cancellationFailure) {
                log.warn("取消上游 AI 流失败，taskId: {}",
                        executionContext.taskId(), LogExceptionSanitizer.sanitize(cancellationFailure));
            }
        }

        /** 取消句柄。 */
        private void cancelHandle(StreamingHandle handle) {
            if (handle == null || handle.isCancelled()) {
                return;
            }
            try {
                handle.cancel();
            } catch (RuntimeException cancellationFailure) {
                log.warn("取消受监督的 AI 流失败，taskId: {}",
                        executionContext.taskId(), LogExceptionSanitizer.sanitize(cancellationFailure));
            }
        }
    }
}
