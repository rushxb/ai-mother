package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.core.handler.GenerationPublicEventSanitizer;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.eventstream.GenerationEventStream;
import com.rush.rushaicodemother.memory.GenerationWorkingMemoryService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspace;
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
    private final Object sharedStreamMonitor = new Object();
    private final AtomicReference<GenerationPreparation> preparationRef = new AtomicReference<>();
    private final GenerationExecutionContext executionContext;
    private final GenerationEventStream generationEventStream;
    private final GenerationWorkingMemoryService workingMemoryService;
    private final Instant startedAt = Instant.now();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean completionStarted = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final AtomicBoolean sharedStreamCompleted = new AtomicBoolean(false);
    private final AtomicReference<GenerationTraceService> traceServiceRef = new AtomicReference<>();
    private final AtomicReference<GenerationCancellationHandle> cancellationHandleRef = new AtomicReference<>();
    private final AtomicReference<GenerationTaskRequest> taskRequestRef = new AtomicReference<>();
    private final AtomicReference<String> routeRef = new AtomicReference<>();
    private final AtomicReference<GenerationExecutionWorkspace> executionWorkspaceRef = new AtomicReference<>();
    private Long appId;
    private Long userId;

    public GenerationSession(GenerationPreparation preparation) {
        this(preparation, null);
    }

    public GenerationSession(GenerationPreparation preparation, GenerationExecutionContext executionContext) {
        this(preparation, executionContext, null);
    }

    GenerationSession(GenerationPreparation preparation,
                      GenerationExecutionContext executionContext,
                      GenerationEventStream generationEventStream) {
        this(preparation, executionContext, generationEventStream, null);
    }

    GenerationSession(GenerationPreparation preparation,
                      GenerationExecutionContext executionContext,
                      GenerationEventStream generationEventStream,
                      GenerationWorkingMemoryService workingMemoryService) {
        this.preparationRef.set(preparation);
        this.executionContext = executionContext;
        this.generationEventStream = generationEventStream;
        this.workingMemoryService = workingMemoryService;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public GenerationPreparation preparation() {
        return preparationRef.get();
    }

    /** Binds preparation once it becomes available after asynchronous task submission. */
    public void bindPreparation(GenerationPreparation preparation) {
        if (preparation == null) {
            throw new IllegalArgumentException("generation preparation cannot be null");
        }
        GenerationPreparation existing = preparationRef.get();
        if (existing == null) {
            preparationRef.compareAndSet(null, preparation);
            existing = preparationRef.get();
        }
        if (existing != preparation && !existing.equals(preparation)) {
            throw new IllegalStateException("generation session preparation is already bound");
        }
    }

    public String taskId() {
        if (executionContext != null) {
            return executionContext.taskId();
        }
        GenerationPreparation preparation = preparation();
        return preparation == null ? null : preparation.taskId();
    }

    public GenerationExecutionContext executionContext() {
        return executionContext;
    }

    /** Binds the writable workspace for the current durable execution epoch. */
    public void bindExecutionWorkspace(GenerationExecutionWorkspace executionWorkspace) {
        if (executionWorkspace == null || taskId() == null
                || !taskId().equals(executionWorkspace.taskId())) {
            throw new IllegalArgumentException("generation execution workspace identity mismatch");
        }
        while (true) {
            GenerationExecutionWorkspace current = executionWorkspaceRef.get();
            if (current == executionWorkspace || executionWorkspace.equals(current)) {
                return;
            }
            if (current != null
                    && (!current.taskId().equals(executionWorkspace.taskId())
                    || executionWorkspace.executionEpoch() < current.executionEpoch())) {
                throw new IllegalStateException("generation execution workspace cannot move backwards");
            }
            if (executionWorkspaceRef.compareAndSet(current, executionWorkspace)) {
                return;
            }
        }
    }

    public GenerationExecutionWorkspace executionWorkspace() {
        return executionWorkspaceRef.get();
    }

    /** Records the currently selected route; fallback may update it while preserving task identity. */
    public void recordRoute(String route) {
        if (route == null || route.isBlank()) {
            throw new IllegalArgumentException("generation route cannot be blank");
        }
        routeRef.set(route.trim());
        if (workingMemoryService != null && executionContext != null) {
            workingMemoryService.initialize(
                    executionContext.taskId(), executionContext.appId(), executionContext.userId(), route.trim());
        }
    }

    public String route() {
        return routeRef.get();
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
        GenerationStreamEvent publicEvent = GenerationPublicEventSanitizer.sanitize(event);
        if (publicEvent == null) {
            return;
        }
        GenerationTraceService generationTraceService = traceServiceRef.get();
        GenerationPreparation preparation = preparation();
        if (generationTraceService != null && preparation != null) {
            generationTraceService.recordEvent(preparation.taskId(), appId, userId, publicEvent);
        } else if (generationTraceService != null || preparation != null) {
            log.warn("生成事件未写入 trace，原因: traceService={}, preparation={}, eventType={}",
                    generationTraceService != null, preparation != null, publicEvent.getType());
        }
        sink.tryEmitNext(publicEvent);
        if (workingMemoryService != null && taskId() != null) {
            workingMemoryService.recordEvent(taskId(), publicEvent);
        }
        publishToSharedStream(publicEvent);
    }

    public void complete() {
        completionStarted.set(true);
        completed.set(true);
        sink.tryEmitComplete();
        if (workingMemoryService != null && taskId() != null) {
            workingMemoryService.complete(taskId());
        }
        completeSharedStream();
    }

    public void error(Throwable throwable) {
        if (!tryBeginCompletion()) {
            return;
        }
        sink.tryEmitError(throwable);
        if (workingMemoryService != null && taskId() != null) {
            workingMemoryService.complete(taskId());
        }
        completeSharedStream();
    }

    public void cancel() {
        cancel("user_requested");
    }

    /** Cancels the session while preserving the first authoritative cancellation reason. */
    public void cancel(String reason) {
        String normalizedReason = reason == null || reason.isBlank() ? "cancelled" : reason.trim();
        if (executionContext != null) {
            executionContext.cancel(normalizedReason);
        }
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        try {
            GenerationCancellationHandle handle = cancellationHandleRef.get();
            if (handle != null) {
                handle.cancel();
            }
        } catch (RuntimeException cancellationFailure) {
            log.warn("Generation cancellation handle failed, taskId: {}, error: {}",
                    taskId(), LogExceptionSanitizer.sanitizeMessage(cancellationFailure));
        } finally {
            cancelSink.tryEmitEmpty();
        }
    }

    public boolean isCancelled() {
        return cancelled.get() || (executionContext != null && executionContext.isCancelled());
    }

    public boolean isActive() {
        return !completionStarted.get();
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

    private void publishToSharedStream(GenerationStreamEvent event) {
        String currentTaskId = taskId();
        if (generationEventStream == null || currentTaskId == null || currentTaskId.isBlank()) {
            return;
        }
        synchronized (sharedStreamMonitor) {
            if (sharedStreamCompleted.get()) {
                return;
            }
            try {
                generationEventStream.publish(currentTaskId, event);
            } catch (RuntimeException failure) {
                log.warn("Failed to publish task event to shared stream, taskId: {}, error: {}",
                        currentTaskId, LogExceptionSanitizer.sanitizeMessage(failure));
            }
        }
    }

    private void completeSharedStream() {
        String currentTaskId = taskId();
        if (generationEventStream == null || currentTaskId == null || currentTaskId.isBlank()) {
            return;
        }
        synchronized (sharedStreamMonitor) {
            if (!sharedStreamCompleted.compareAndSet(false, true)) {
                return;
            }
            try {
                generationEventStream.complete(currentTaskId);
            } catch (RuntimeException failure) {
                log.warn("Failed to complete shared task event stream, taskId: {}, error: {}",
                        currentTaskId, LogExceptionSanitizer.sanitizeMessage(failure));
            }
        }
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
