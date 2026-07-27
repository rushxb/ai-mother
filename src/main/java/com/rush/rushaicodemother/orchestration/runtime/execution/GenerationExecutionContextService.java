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
 * 活动执行上下文的注册表和生命周期外观。
 *
 * <p>该服务故意公开狭窄的API。当前的实施是本地的，并且是
 * 兼容层将被持久的租赁支持存储库取代，而无需
 * 更改模型、工具或构建集成。</p>
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

    /** 当任务由该运行时管理时保留预算单位。 */
    public boolean consumeIfPresent(String taskId, GenerationBudgetKind kind) {
        return consumeIfPresent(taskId, kind, 1);
    }

    /** 任务受管时原子预留多个预算单位。 */
    public boolean consumeIfPresent(String taskId, GenerationBudgetKind kind, int units) {
        Optional<GenerationExecutionContext> context = getByTaskId(taskId);
        context.ifPresent(value -> value.consume(kind, units));
        return context.isPresent();
    }

    /** 任务受管时记录已经实际落盘的工作区变更。 */
    public boolean recordSuccessfulWorkspaceMutationsIfPresent(String taskId, int operationCount) {
        Optional<GenerationExecutionContext> context = getByTaskId(taskId);
        context.ifPresent(value -> value.recordSuccessfulWorkspaceMutations(operationCount));
        return context.isPresent();
    }

    /** 将操作超时限制在任务截止日期内，同时保留旧调用者。 */
    public Duration clampTimeout(String taskId, Duration configuredTimeout) {
        return getByTaskId(taskId)
                .map(context -> context.clampTimeout(configuredTimeout))
                .orElse(configuredTimeout);
    }

    /** 当正在运行的外部操作应立即终止时返回 true。 */
    public boolean shouldStop(String taskId) {
        return getByTaskId(taskId)
                .map(context -> context.isCancelled() || context.isDeadlineExceeded() || context.isCompleted())
                .orElse(false);
    }

    /** 外部操作停止后强制执行取消和截止时间状态。 */
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
     * 仅当上下文仍绑定到调用者的持久执行栅栏时才完成上下文。
     *
     * <p>Dispatch 清理可以与租约恢复和批准继续进行竞争。一个
     * 因此，简单的任务 ID 删除可能会破坏新纪元的上下文。这个有条件的
     * 当所有权移动时，变体使清理失败关闭。</p>
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
