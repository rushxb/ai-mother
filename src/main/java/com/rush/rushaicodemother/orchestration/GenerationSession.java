package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.service.GenerationTraceService;
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
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final AtomicReference<GenerationTraceService> traceServiceRef = new AtomicReference<>();
    private final AtomicReference<GenerationCancellationHandle> cancellationHandleRef = new AtomicReference<>();
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

    public void bindTraceContext(GenerationTraceService generationTraceService, Long appId, Long userId) {
        this.traceServiceRef.set(generationTraceService);
        this.appId = appId;
        this.userId = userId;
    }

    public boolean tryMarkCompleted() {
        return completed.compareAndSet(false, true);
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
        completed.set(true);
        sink.tryEmitComplete();
    }

    public void error(Throwable throwable) {
        if (!tryMarkCompleted()) {
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
        return !completed.get() && !cancelled.get();
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
