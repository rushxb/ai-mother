package com.rush.rushaicodemother.orchestration.runtime.execution;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry and lifecycle facade for active execution contexts.
 *
 * <p>This service deliberately exposes a narrow API. The current implementation is local and is
 * the compatibility layer that will be replaced by a durable lease-backed repository without
 * changing model, tool or build integrations.</p>
 */
@Service
public class GenerationExecutionContextService {

    private final GenerationRuntimeProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, GenerationExecutionContext> contextsByTaskId = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, String> taskIdsByAppId = new ConcurrentHashMap<>();

    @Autowired
    public GenerationExecutionContextService(GenerationRuntimeProperties properties) {
        this(properties, Clock.systemUTC());
    }

    GenerationExecutionContextService(GenerationRuntimeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public GenerationExecutionContext start(String taskId, Long appId, Long userId) {
        GenerationExecutionContext context = new GenerationExecutionContext(
                taskId,
                appId,
                userId,
                Instant.now(clock),
                properties.toLimits(),
                clock
        );
        GenerationExecutionContext existingTask = contextsByTaskId.putIfAbsent(taskId, context);
        if (existingTask != null) {
            return existingTask;
        }
        if (appId != null) {
            String existingTaskId = taskIdsByAppId.putIfAbsent(appId, taskId);
            if (existingTaskId != null && !existingTaskId.equals(taskId)) {
                contextsByTaskId.remove(taskId, context);
                throw new GenerationExecutionPolicyException(
                        "应用已有运行中的生成任务，appId=" + appId + ", taskId=" + existingTaskId
                );
            }
        }
        return context;
    }

    public GenerationExecutionContext restore(GenerationExecutionSnapshot snapshot,
                                              GenerationExecutionLimits limits) {
        GenerationExecutionContext context = GenerationExecutionContext.restore(snapshot, limits, clock);
        GenerationExecutionContext existingTask = contextsByTaskId.putIfAbsent(snapshot.taskId(), context);
        if (existingTask != null) {
            return existingTask;
        }
        if (snapshot.appId() != null) {
            String existingTaskId = taskIdsByAppId.putIfAbsent(snapshot.appId(), snapshot.taskId());
            if (existingTaskId != null && !existingTaskId.equals(snapshot.taskId())) {
                contextsByTaskId.remove(snapshot.taskId(), context);
                throw new GenerationExecutionPolicyException(
                        "application already has a different active generation context");
            }
        }
        return context;
    }

    public Optional<GenerationExecutionContext> getByTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(contextsByTaskId.get(taskId));
    }

    public Optional<GenerationExecutionContext> getByAppId(Long appId) {
        if (appId == null) {
            return Optional.empty();
        }
        String taskId = taskIdsByAppId.get(appId);
        return taskId == null ? Optional.empty() : getByTaskId(taskId);
    }

    public void bindExecutionFence(String taskId, GenerationExecutionFence fence) {
        GenerationExecutionContext context = getByTaskId(taskId)
                .orElseThrow(() -> new GenerationExecutionPolicyException(
                        "generation execution context does not exist for fence binding"));
        synchronized (context) {
            context.bindExecutionFence(fence);
        }
    }

    public Optional<GenerationExecutionFence> getExecutionFence(String taskId) {
        return getByTaskId(taskId).map(GenerationExecutionContext::executionFence);
    }

    /** Reserves a budget unit when the task is managed by this runtime. */
    public boolean consumeIfPresent(String taskId, GenerationBudgetKind kind) {
        Optional<GenerationExecutionContext> context = getByTaskId(taskId);
        context.ifPresent(value -> value.consume(kind));
        return context.isPresent();
    }

    /** Clamps an operation timeout to the task deadline while preserving legacy callers. */
    public Duration clampTimeout(String taskId, Duration configuredTimeout) {
        return getByTaskId(taskId)
                .map(context -> context.clampTimeout(configuredTimeout))
                .orElse(configuredTimeout);
    }

    /** Returns true when a running external operation should terminate promptly. */
    public boolean shouldStop(String taskId) {
        return getByTaskId(taskId)
                .map(context -> context.isCancelled() || context.isDeadlineExceeded() || context.isCompleted())
                .orElse(false);
    }

    /** Enforces cancellation and deadline state after an external operation stops. */
    public void assertCanContinue(String taskId) {
        getByTaskId(taskId).ifPresent(GenerationExecutionContext::assertCanContinue);
    }

    public void cancelByTaskId(String taskId, String reason) {
        getByTaskId(taskId).ifPresent(context -> context.cancel(reason));
    }

    public void cancelByAppId(Long appId, String reason) {
        getByAppId(appId).ifPresent(context -> context.cancel(reason));
    }

    public void finish(String taskId, String status) {
        GenerationExecutionContext context = contextsByTaskId.remove(taskId);
        if (context == null) {
            return;
        }
        context.complete(status);
        if (context.appId() != null) {
            taskIdsByAppId.remove(context.appId(), taskId);
        }
    }

    /**
     * Finishes a context only when it is still bound to the caller's durable execution fence.
     *
     * <p>Dispatch cleanup is allowed to race with lease recovery and approval continuation. A
     * plain task-id removal could therefore tear down a newer epoch's context. This conditional
     * variant makes cleanup fail closed when ownership has moved.</p>
     */
    public boolean finishIfOwned(String taskId, GenerationExecutionFence fence, String status) {
        if (taskId == null || taskId.isBlank() || fence == null || !taskId.equals(fence.taskId())) {
            return false;
        }
        GenerationExecutionContext context = contextsByTaskId.get(taskId);
        if (context == null || !fence.equals(context.executionFence())) {
            return false;
        }
        synchronized (context) {
            if (!fence.equals(context.executionFence()) || !contextsByTaskId.remove(taskId, context)) {
                return false;
            }
            context.complete(status);
            if (context.appId() != null) {
                taskIdsByAppId.remove(context.appId(), taskId);
            }
            return true;
        }
    }

    public void finishByAppId(Long appId, String status) {
        if (appId == null) {
            return;
        }
        String taskId = taskIdsByAppId.get(appId);
        if (taskId != null) {
            finish(taskId, status);
        }
    }
}
