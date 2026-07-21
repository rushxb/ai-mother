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

    /** Internal-only InvocationParameters key; never rendered into a prompt or tool argument. */
    public static final String EXECUTION_FENCE_PARAMETER = "generation.execution.fence";

    private final ConcurrentMap<Long, GenerationToolExecutionContext> contexts = new ConcurrentHashMap<>();
    private final ConcurrentMap<GenerationExecutionFence, GenerationToolExecutionContext> fencedContexts =
            new ConcurrentHashMap<>();
    private final ThreadLocal<ToolInvocationExecution> activeInvocation = new ThreadLocal<>();
    private final ThreadLocal<GenerationExecutionFence> activeFence = new ThreadLocal<>();

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
            // An exact-fence lookup must fail closed. Falling back to the mutable app-level slot
            // would let a late callback write into a newer execution epoch.
            return Optional.empty();
        }
        return Optional.ofNullable(contexts.get(appId));
    }

    /** Looks up a context by the immutable fence captured by a model invocation. */
    public Optional<GenerationToolExecutionContext> getContext(GenerationExecutionFence fence) {
        return fence == null ? Optional.empty() : Optional.ofNullable(fencedContexts.get(fence));
    }

    /**
     * Resolves the exact context associated with a LangChain4j invocation. If the invocation carries
     * a fence, falling back to the app-wide context is deliberately forbidden: that would let a late
     * callback use a newer epoch's workspace.
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
     * Binds tool policy together with the immutable execution identity selected by the worker.
     *
     * <p>Post-generation validation can replace the change plan after the model stream has
     * crossed an asynchronous boundary.  The old overload intentionally remains available for
     * legacy callers, but managed executions must use this overload so a policy refresh cannot
     * silently drop the fenced workspace.</p>
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

    /** Binds the exact isolated workspace after orchestration has selected its target type. */
    public void bindWorkspace(Long appId, String taskId, GenerationWorkspace workspace) {
        bindWorkspace(appId, taskId, workspace, activeFence.get());
    }

    /** Binds a workspace to the exact durable epoch rather than to an app-wide mutable slot. */
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

    /** Associates the current app/task context with the durable execution epoch. */
    public void bindExecutionFence(Long appId,
                                   String taskId,
                                   GenerationExecutionFence fence) {
        if (!bindExecutionFenceIfPresent(appId, taskId, fence)) {
            throw new IllegalStateException("tool execution context does not exist");
        }
    }

    /**
     * Pins an already prepared tool policy to a durable epoch without manufacturing a policy.
     *
     * <p>CREATE and non-tool edit routes legitimately reach worker admission before any tool
     * context exists.  Dispatch may pin a context prepared by an earlier phase, but it must not
     * fail those routes or create a permissive placeholder merely to attach a fence.</p>
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

    /** Clears only the context owned by one task; a newer task for the same app is preserved. */
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
     * Clears a task context only when the supplied durable fence is still the current owner.
     *
     * <p>This is intentionally stricter than {@link #clearContext(Long, String)}. Dispatch
     * cleanup can run after a retry or approval continuation has already installed a newer
     * execution epoch; an old worker must not remove that newer worker's app-level fallback
     * context.</p>
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

    /** Activates an exact fence for code called by a tool executor on the current thread. */
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

    public Optional<ToolInvocationExecution> currentInvocation() {
        return Optional.ofNullable(activeInvocation.get());
    }

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
            String requestId,
            String toolName,
            String argumentsDigest
    ) {

        public ToolInvocationExecution {
            if (taskId == null || taskId.isBlank()
                    || requestId == null || requestId.isBlank()
                    || toolName == null || toolName.isBlank()
                    || argumentsDigest == null || argumentsDigest.isBlank()) {
                throw new IllegalArgumentException("tool invocation identity is incomplete");
            }
        }
    }
}
