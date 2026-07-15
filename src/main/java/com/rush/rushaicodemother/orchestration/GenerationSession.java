package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public final class GenerationSession {

    public static final int MAX_REPLAY_EVENTS = 500;

    private final Sinks.Many<GenerationStreamEvent> sink = Sinks.many().replay().limit(MAX_REPLAY_EVENTS);
    private final Sinks.Empty<Void> cancelSink = Sinks.empty();
    private final GenerationPreparation preparation;
    private final GenerationExecutionContext executionContext;
    private final Instant startedAt = Instant.now();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean completionStarted = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final AtomicReference<GenerationTraceService> traceServiceRef = new AtomicReference<>();
    private final AtomicReference<GenerationCancellationHandle> cancellationHandleRef = new AtomicReference<>();
    private final AtomicReference<GenerationTaskRequest> taskRequestRef = new AtomicReference<>();
    private Long appId;
    private Long userId;

    public GenerationSession(GenerationPreparation preparation) {
        this(preparation, null);
    }

    public GenerationSession(GenerationPreparation preparation, GenerationExecutionContext executionContext) {
        this.preparation = preparation;
        this.executionContext = executionContext;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public GenerationPreparation preparation() {
        return preparation;
    }

    public GenerationExecutionContext executionContext() {
        return executionContext;
    }

    /** Binds immutable request metadata required to publish a terminal task event from any completion path. */
    public void bindTaskRequest(GenerationTaskRequest taskRequest) {
        if (taskRequest == null) {
            return;
        }
        GenerationTaskRequest existing = taskRequestRef.get();
        if (existing == null) {
            taskRequestRef.compareAndSet(null, taskRequest);
            existing = taskRequestRef.get();
        }
        if (existing != taskRequest && !existing.equals(taskRequest)) {
            throw new IllegalStateException("generation session request is already bound");
        }
    }

    public GenerationTaskRequest taskRequest() {
        return taskRequestRef.get();
    }

    public void bindTraceContext(GenerationTraceService generationTraceService, Long appId, Long userId) {
        this.traceServiceRef.set(generationTraceService);
        this.appId = appId;
        this.userId = userId;
    }

    /**
     * Claims terminalization for exactly one caller while still allowing it to emit the final stream event.
     */
    public boolean tryBeginCompletion() {
        return completionStarted.compareAndSet(false, true);
    }

    public Flux<GenerationStreamEvent> asFlux() {
        return sink.asFlux();
    }

    public void emit(GenerationStreamEvent event) {
        if (completed.get()) {
            return;
        }
        GenerationTraceService generationTraceService = traceServiceRef.get();
        if (generationTraceService != null && preparation != null) {
            generationTraceService.recordEvent(preparation.taskId(), appId, userId, event);
        } else if (generationTraceService != null || preparation != null) {
            log.warn("生成事件未写入 trace，原因: traceService={}, preparation={}, eventType={}",
                    generationTraceService != null, preparation != null, event == null ? null : event.getType());
        }
        sink.tryEmitNext(event);
    }

    public void complete() {
        completionStarted.set(true);
        completed.set(true);
        sink.tryEmitComplete();
    }

    public void error(Throwable throwable) {
        if (!tryBeginCompletion()) {
            return;
        }
        sink.tryEmitError(throwable);
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            if (executionContext != null) {
                executionContext.cancel("user_requested");
            }
            GenerationCancellationHandle handle = cancellationHandleRef.get();
            if (handle != null) {
                handle.cancel();
            }
            cancelSink.tryEmitEmpty();
        }
    }

    public boolean isCancelled() {
        return cancelled.get() || (executionContext != null && executionContext.isCancelled());
    }

    public boolean isActive() {
        return !completionStarted.get() && !isCancelled();
    }

    /**
     * Reserves one unit from the task-wide budget when this session is runtime-managed.
     * Legacy sessions without an execution context remain supported for isolated tests and old routes.
     */
    public int consumeBudget(GenerationBudgetKind kind) {
        return executionContext == null ? 0 : executionContext.consume(kind);
    }

    public boolean hasRemainingBudget(GenerationBudgetKind kind) {
        return executionContext != null && executionContext.hasRemainingBudget(kind);
    }

    /** Returns the immutable task-level limit; unmanaged legacy sessions expose no automatic-repair budget. */
    public int budgetLimit(GenerationBudgetKind kind) {
        return executionContext == null ? 0 : executionContext.limit(kind);
    }

    public int remainingBudget(GenerationBudgetKind kind) {
        return executionContext == null ? 0 : executionContext.remaining(kind);
    }

    public Flux<Void> cancelSignal() {
        return cancelSink.asMono().flux();
    }

    public void setCancellationHandle(GenerationCancellationHandle cancellationHandle) {
        GenerationCancellationHandle idempotentHandle = idempotent(cancellationHandle);
        cancellationHandleRef.set(idempotentHandle);
        if (cancelled.get() && idempotentHandle != null) {
            idempotentHandle.cancel();
        }
    }

    private GenerationCancellationHandle idempotent(GenerationCancellationHandle cancellationHandle) {
        if (cancellationHandle == null) {
            return null;
        }
        AtomicBoolean cancellationInvoked = new AtomicBoolean(false);
        return () -> {
            if (cancellationInvoked.compareAndSet(false, true)) {
                cancellationHandle.cancel();
            }
        };
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new GenerationStoppedException();
        }
        if (executionContext != null) {
            executionContext.assertCanContinue();
        }
    }

    public void emitStopped() {
        emit(GenerationStreamEvent.generationStopped("\n\n[系统] 已停止本次生成\n\n", Map.of(
                "message", "已停止本次生成"
        )));
    }
}
