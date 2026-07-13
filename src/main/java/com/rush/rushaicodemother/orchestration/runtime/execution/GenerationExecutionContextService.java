package com.rush.rushaicodemother.orchestration.runtime.execution;

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
