package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.core.handler.GenerationPublicEventSanitizer;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.eventstream.GenerationEventStream;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
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
    private final AtomicReference<GenerationExecutionPlan> executionPlanRef = new AtomicReference<>();
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

    /** 异步任务提交后，一旦准备变得可用，就将其绑定。 */
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

    /**
 * 返回任务编号。
 *
 * @return 处理后的生成会话文本
 */
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

    /** 绑定当前持久执行时期的可写工作空间。 */
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

    /** 绑定任务提交时冻结的执行计划；同一会话不允许切换为另一份计划。 */
    public void bindExecutionPlan(GenerationExecutionPlan executionPlan) {
        if (executionPlan == null) {
            return;
        }
        GenerationExecutionPlan existing = executionPlanRef.get();
        if (existing == null) {
            executionPlanRef.compareAndSet(null, executionPlan);
            existing = executionPlanRef.get();
        }
        if (existing != executionPlan && !existing.equals(executionPlan)) {
            throw new IllegalStateException("生成会话执行计划已绑定，不能重复替换");
        }
    }

    public GenerationExecutionPlan executionPlan() {
        return executionPlanRef.get();
    }
    /** 记录当前选择的路线；回退可以在保留任务标识的同时更新它。 */
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

    /** 绑定从任何完成路径发布终端任务事件所需的不可变请求元数据。 */
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

    /**
 * 绑定追踪上下文。
 *
 * @param generationTraceService 生成追踪服务
 * @param appId 应用编号
 * @param userId 用户编号
 */
    public void bindTraceContext(GenerationTraceService generationTraceService, Long appId, Long userId) {
        this.traceServiceRef.set(generationTraceService);
        this.appId = appId;
        this.userId = userId;
    }

    /**
     * 声明恰好一个调用者终止，同时仍然允许它发出最终的流事件。
     */
    public boolean tryBeginCompletion() {
        return completionStarted.compareAndSet(false, true);
    }

    public Flux<GenerationStreamEvent> asFlux() {
        return sink.asFlux();
    }

    /**
 * 发送生成会话事件。
 *
 * @param event 待处理的领域事件
 */
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

    /** 完成生成会话并持久化终态。 */
    public void complete() {
        completionStarted.set(true);
        completed.set(true);
        sink.tryEmitComplete();
        if (workingMemoryService != null && taskId() != null) {
            workingMemoryService.complete(taskId());
        }
        completeSharedStream();
    }

    /**
 * 处理错误。
 *
 * @param throwable 待处理的异常
 */
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

    /** 取消会话，同时保留第一个权威取消原因。 */
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
     * 当此会话由运行时管理时，从任务范围预算中保留一个单位。
     * 没有执行上下文的旧会话仍然支持隔离测试和旧路由。
     */
    public int consumeBudget(GenerationBudgetKind kind) {
        return executionContext == null ? 0 : executionContext.consume(kind);
    }

    public boolean hasRemainingBudget(GenerationBudgetKind kind) {
        return executionContext != null && executionContext.hasRemainingBudget(kind);
    }

    /** 返回不可变的任务级别限制；非托管遗留会话不公开任何自动修复预算。 */
    public int budgetLimit(GenerationBudgetKind kind) {
        return executionContext == null ? 0 : executionContext.limit(kind);
    }

    public int remainingBudget(GenerationBudgetKind kind) {
        return executionContext == null ? 0 : executionContext.remaining(kind);
    }

    /**
 * 取消{@code Signal}。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public Flux<Void> cancelSignal() {
        return cancelSink.asMono().flux();
    }

    /**
 * 处理集合{@code Cancellation}句柄。
 *
 * @param cancellationHandle {@code cancellationHandle} 对应的调用参数
 */
    public void setCancellationHandle(GenerationCancellationHandle cancellationHandle) {
        GenerationCancellationHandle idempotentHandle = idempotent(cancellationHandle);
        cancellationHandleRef.set(idempotentHandle);
        if (cancelled.get() && idempotentHandle != null) {
            idempotentHandle.cancel();
        }
    }

    /** 返回{@code idempotent}。 */
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

    /** 发布{@code To}{@code Shared}流。 */
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

    /** 完成{@code Shared}流并持久化终态。 */
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

    /** 处理{@code throw}{@code If}{@code Cancelled}。 */
    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new GenerationStoppedException();
        }
        if (executionContext != null) {
            executionContext.assertCanContinue();
        }
    }

    /** 发送{@code Stopped}事件。 */
    public void emitStopped() {
        emit(GenerationStreamEvent.generationStopped("\n\n[系统] 已停止本次生成\n\n", Map.of(
                "message", "已停止本次生成"
        )));
    }
}
