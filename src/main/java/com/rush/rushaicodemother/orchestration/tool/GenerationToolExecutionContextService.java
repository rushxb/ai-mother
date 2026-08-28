package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import dev.langchain4j.invocation.InvocationContext;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

@Component
public class GenerationToolExecutionContextService {

    /** 仅供内部使用的 InspirationParameters 键；从未呈现为提示或工具参数。 */
    public static final String EXECUTION_FENCE_PARAMETER = "generation.execution.fence";

    private final ConcurrentMap<Long, GenerationToolExecutionContext> contexts = new ConcurrentHashMap<>();
    private final ConcurrentMap<GenerationExecutionFence, GenerationToolExecutionContext> fencedContexts =
            new ConcurrentHashMap<>();
    private final ThreadLocal<ToolInvocationExecution> activeInvocation = new ThreadLocal<>();
    private final ThreadLocal<GenerationExecutionFence> activeFence = new ThreadLocal<>();

    /**
 * 获取并返回上下文。
 *
 * @param appId 应用编号
 * @return 可选的生成工具执行上下文；不存在时返回空值
 */
    public Optional<GenerationToolExecutionContext> getContext(Long appId) {
        if (appId == null) {
            return Optional.empty();
        }
        GenerationExecutionFence fence = activeFence.get();
        if (fence != null) {
            GenerationToolExecutionContext fenced = fencedContexts.get(fence);
            if (fenced != null && Objects.equals(appId, fenced.appId())) {
                return Optional.of(fenced);
            }
            // 精确围栏查找必须失败关闭。回落到可变的应用程序级插槽
            // 会让延迟回调写入较新的执行纪元。
            return Optional.empty();
        }
        return Optional.ofNullable(contexts.get(appId));
    }

    /** 通过模型调用捕获的不可变栅栏查找上下文。 */
    public Optional<GenerationToolExecutionContext> getContext(GenerationExecutionFence fence) {
        return fence == null ? Optional.empty() : Optional.ofNullable(fencedContexts.get(fence));
    }

    /**
     * 解析与 LangChain4j 调用相关的确切上下文。如果调用携带
     * 故意禁止栅栏，回退到应用程序范围的上下文：这会让迟到的人
     * 回调使用较新纪元的工作区。
     */
    public Optional<GenerationToolExecutionContext> getContextForInvocation(InvocationContext invocationContext) {
        if (invocationContext == null) {
            return Optional.empty();
        }
        Object fenceValue = invocationContext.invocationParameters() == null
                ? null
                : invocationContext.invocationParameters().get(EXECUTION_FENCE_PARAMETER);
        if (fenceValue != null) {
            return fenceValue instanceof GenerationExecutionFence fence
                    ? getContext(fence)
                    : Optional.empty();
        }
        Object memoryId = invocationContext.chatMemoryId();
        return memoryId instanceof Number number
                ? getContext(number.longValue())
                : Optional.empty();
    }

    /**
 * 绑定上下文。
 *
 * @param context 执行上下文
 */
    public void bindContext(GenerationToolExecutionContext context) {
        if (context == null || context.appId() == null) {
            return;
        }
        contexts.put(context.appId(), context);
        if (context.executionFence() != null) {
            fencedContexts.put(context.executionFence(), context);
        }
    }

    public void bindChangePlan(Long appId, String taskId, String generationMode, CodeGenTypeEnum codeGenType, ChangePlan changePlan, boolean allowUnplannedWrite, String reason) {
        bindContext(new GenerationToolExecutionContext(appId, taskId, generationMode, codeGenType, changePlan, allowUnplannedWrite, reason));
    }

    /**
     * 将工具策略与工作人员选择的不可变执行身份绑定在一起。
     *
     * <p>生成后验证可以在模型流完成后替换变更计划
     * 跨越了异步边界。  旧的重载有意保留为
     * 遗留调用者，但托管执行必须使用此重载，因此策略刷新无法
     * 默默地放下围栏工作区。</p>
     */
    public void bindChangePlan(Long appId,
                               String taskId,
                               String generationMode,
                               CodeGenTypeEnum codeGenType,
                               ChangePlan changePlan,
                               boolean allowUnplannedWrite,
                               String reason,
                               GenerationWorkspace workspace,
                               GenerationExecutionFence fence) {
        if (fence != null && (taskId == null || !taskId.equals(fence.taskId()))) {
            throw new IllegalArgumentException("generation tool execution fence task identity mismatch");
        }
        bindContext(new GenerationToolExecutionContext(
                appId, taskId, generationMode, codeGenType, changePlan,
                allowUnplannedWrite, reason, workspace, fence));
    }

    /**
 * 绑定{@code Change}计划。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param generationMode 生成模式
 * @param codeGenType 代码生成类型
 * @param changePlan {@code changePlan} 对应的调用参数
 * @param allowUnplannedWrite {@code allowUnplannedWrite} 对应的调用参数
 * @param reason 原因
 * @param workspace 工作区
 */
    public void bindChangePlan(Long appId,
                               String taskId,
                               String generationMode,
                               CodeGenTypeEnum codeGenType,
                               ChangePlan changePlan,
                               boolean allowUnplannedWrite,
                               String reason,
                               GenerationWorkspace workspace) {
        bindContext(new GenerationToolExecutionContext(
                appId, taskId, generationMode, codeGenType, changePlan,
                allowUnplannedWrite, reason, workspace));
    }

    /** 在编排选择其目标类型后，绑定确切的隔离工作区。 */
    public void bindWorkspace(Long appId, String taskId, GenerationWorkspace workspace) {
        bindWorkspace(appId, taskId, workspace, activeFence.get());
    }

    /** 将工作区绑定到确切的持久纪元，而不是绑定到应用程序范围的可变槽。 */
    public void bindWorkspace(Long appId,
                              String taskId,
                              GenerationWorkspace workspace,
                              GenerationExecutionFence fence) {
        if (appId == null || taskId == null || workspace == null) {
            return;
        }
        contexts.computeIfPresent(appId, (ignored, current) -> {
            if (!taskId.equals(current.taskId())) {
                throw new IllegalStateException("tool execution context task identity mismatch");
            }
            GenerationToolExecutionContext updated = new GenerationToolExecutionContext(
                    current.appId(), current.taskId(), current.generationMode(), current.codeGenType(),
                    current.changePlan(), current.allowUnplannedWrite(), current.reason(), workspace,
                    fence == null ? current.executionFence() : fence);
            if (updated.executionFence() != null) {
                fencedContexts.put(updated.executionFence(), updated);
            }
            return updated;
        });
    }

    /** 将当前应用程序/任务上下文与持久执行纪元相关联。 */
    public void bindExecutionFence(Long appId,
                                   String taskId,
                                   GenerationExecutionFence fence) {
        if (!bindExecutionFenceIfPresent(appId, taskId, fence)) {
            throw new IllegalStateException("tool execution context does not exist");
        }
    }

    /**
     * 将已经准备好的工具政策固定到一个持久的时代，而不需要制定政策。
     *
     * <p>CREATE 和非工具编辑路径在任何工具之前合法到达工作人员许可
     * 上下文存在。  调度可以固定早期阶段准备的上下文，但它不能
     * 使这些路线失败或创建一个允许的占位符只是为了附加围栏。</p>
     */
    public boolean bindExecutionFenceIfPresent(Long appId,
                                               String taskId,
                                               GenerationExecutionFence fence) {
        if (appId == null || taskId == null || fence == null) {
            return false;
        }
        if (!taskId.equals(fence.taskId())) {
            throw new IllegalArgumentException("tool execution fence task identity mismatch");
        }
        java.util.concurrent.atomic.AtomicBoolean bound = new java.util.concurrent.atomic.AtomicBoolean();
        contexts.computeIfPresent(appId, (ignored, current) -> {
            if (!taskId.equals(current.taskId())) {
                throw new IllegalStateException("tool execution context task identity mismatch");
            }
            GenerationToolExecutionContext updated = new GenerationToolExecutionContext(
                    current.appId(), current.taskId(), current.generationMode(), current.codeGenType(),
                    current.changePlan(), current.allowUnplannedWrite(), current.reason(), current.workspace(), fence);
            fencedContexts.put(fence, updated);
            bound.set(true);
            return updated;
        });
        return bound.get();
    }

    /**
 * 清理上下文。
 *
 * @param appId 应用编号
 */
    public void clearContext(Long appId) {
        if (appId != null) {
            GenerationToolExecutionContext removed = contexts.remove(appId);
            fencedContexts.entrySet().removeIf(entry ->
                    removed != null && Objects.equals(entry.getValue().taskId(), removed.taskId())
                            || Objects.equals(entry.getValue().appId(), appId));
            if (Objects.equals(activeFence.get(), removed == null ? null : removed.executionFence())) {
                activeFence.remove();
            }
        }
    }

    /** 仅清除一项任务所拥有的上下文；同一应用程序的新任务将被保留。 */
    public void clearContext(Long appId, String taskId) {
        if (appId == null || taskId == null) {
            return;
        }
        GenerationToolExecutionContext current = contexts.get(appId);
        if (current != null && taskId.equals(current.taskId())) {
            contexts.remove(appId, current);
        }
        fencedContexts.entrySet().removeIf(entry ->
                Objects.equals(entry.getValue().appId(), appId)
                        && taskId.equals(entry.getValue().taskId()));
        GenerationExecutionFence fence = activeFence.get();
        if (fence != null && taskId.equals(fence.taskId())) {
            activeFence.remove();
        }
    }

    /**
     * 仅当提供的持久围栏仍然是当前所有者时才清除任务上下文。
     *
     * <p> 故意比 {@link #clearContext(Long, String)} 更严格。派遣
     * 重试或批准继续已安装更新版本后可以运行清理
     * 执行纪元；旧工作人员不得删除新工作人员的应用程序级回退
     * 上下文.</p>
     */
    public void clearContext(Long appId, String taskId, GenerationExecutionFence fence) {
        if (appId == null || taskId == null || fence == null || !taskId.equals(fence.taskId())) {
            return;
        }
        GenerationToolExecutionContext current = contexts.get(appId);
        if (current != null
                && taskId.equals(current.taskId())
                && fence.equals(current.executionFence())) {
            contexts.remove(appId, current);
        }
        fencedContexts.remove(fence);
        GenerationExecutionFence active = activeFence.get();
        if (fence.equals(active)) {
            activeFence.remove();
        }
    }

    /**
 * 清理围栏上下文。
 *
 * @param fence 围栏
 */
    public void clearFenceContext(GenerationExecutionFence fence) {
        if (fence == null) {
            return;
        }
        GenerationToolExecutionContext removed = fencedContexts.remove(fence);
        if (removed != null) {
            GenerationToolExecutionContext current = contexts.get(removed.appId());
            if (current != null && fence.equals(current.executionFence())) {
                contexts.remove(removed.appId(), current);
            }
        }
        if (fence.equals(activeFence.get())) {
            activeFence.remove();
        }
    }

    /** 为当前线程上的工具执行器调用的代码激活精确的栅栏。 */
    public void activateFence(GenerationExecutionFence fence) {
        if (fence == null) {
            activeFence.remove();
            return;
        }
        activeFence.set(fence);
    }

    public void clearActiveFence() {
        activeFence.remove();
    }

    /**
 * 创建包含围栏的新对象。
 *
 * @param fence 围栏
 * @param action 动作
 * @return 围栏
 */
    public <T> T withFence(GenerationExecutionFence fence, Supplier<T> action) {
        if (fence == null || action == null) {
            throw new IllegalArgumentException("tool execution fence scope is incomplete");
        }
        GenerationExecutionFence previous = activeFence.get();
        activeFence.set(fence);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                activeFence.remove();
            } else {
                activeFence.set(previous);
            }
        }
    }

    /**
 * 返回当前调用。
 *
 * @return 可选的生成工具执行上下文；不存在时返回空值
 */
    public Optional<ToolInvocationExecution> currentInvocation() {
        return Optional.ofNullable(activeInvocation.get());
    }

    /**
 * 创建包含调用的新对象。
 *
 * @param invocation 调用
 * @param action 动作
 * @return 调用
 */
    public <T> T withInvocation(ToolInvocationExecution invocation, Supplier<T> action) {
        if (invocation == null || action == null) {
            throw new IllegalArgumentException("tool invocation scope is incomplete");
        }
        ToolInvocationExecution previous = activeInvocation.get();
        activeInvocation.set(invocation);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                activeInvocation.remove();
            } else {
                activeInvocation.set(previous);
            }
        }
    }

    public record ToolInvocationExecution(
            String taskId,
            long requestExecutionEpoch,
            String requestId,
            String toolName,
            String argumentsDigest
    ) {

        /** 创建工具调用执行实例并完成必要的依赖和初始状态设置。 */
        public ToolInvocationExecution {
            if (taskId == null || taskId.isBlank()
                    || requestExecutionEpoch <= 0
                    || requestId == null || requestId.isBlank()
                    || toolName == null || toolName.isBlank()
                    || argumentsDigest == null || argumentsDigest.isBlank()) {
                throw new IllegalArgumentException("tool invocation identity is incomplete");
            }
        }
    }
}
