package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Propagates an execution-owned workspace across synchronous generation boundaries.
 *
 * <p>The registry is keyed by the full fence, not only by task id. An old worker therefore keeps
 * resolving its own epoch even after a newer worker has claimed the same durable task.</p>
 */
@Component
public class GenerationWorkspaceExecutionScope {

    private final ConcurrentMap<GenerationExecutionFence, ScopeState> states = new ConcurrentHashMap<>();
    private final ThreadLocal<GenerationExecutionFence> currentFence = new ThreadLocal<>();

    public void register(GenerationExecutionFence fence,
                         Long appId,
                         CodeGenTypeEnum baseCodeGenType,
                         Function<CodeGenTypeEnum, GenerationExecutionWorkspace> materializer) {
        Objects.requireNonNull(fence, "fence");
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("appId must be positive");
        }
        Objects.requireNonNull(baseCodeGenType, "baseCodeGenType");
        Objects.requireNonNull(materializer, "materializer");
        ScopeState proposed = new ScopeState(appId, baseCodeGenType, materializer);
        ScopeState existing = states.putIfAbsent(fence, proposed);
        if (existing != null && (!Objects.equals(existing.appId, appId)
                || existing.baseCodeGenType != baseCodeGenType)) {
            throw new IllegalStateException("execution workspace scope identity conflict");
        }
    }

    public GenerationExecutionWorkspace require(GenerationExecutionFence fence,
                                                Long appId,
                                                CodeGenTypeEnum codeGenType) {
        ScopeState state = states.get(Objects.requireNonNull(fence, "fence"));
        if (state == null || !Objects.equals(state.appId, appId)) {
            throw new IllegalStateException("execution workspace scope is not registered");
        }
        return state.workspaces.computeIfAbsent(
                Objects.requireNonNull(codeGenType, "codeGenType"),
                state.materializer
        );
    }

    public Optional<GenerationExecutionWorkspace> current(Long appId, CodeGenTypeEnum codeGenType) {
        GenerationExecutionFence fence = currentFence.get();
        if (fence == null) {
            return Optional.empty();
        }
        return Optional.of(require(fence, appId, codeGenType));
    }

    public Optional<GenerationExecutionWorkspace> find(GenerationExecutionFence fence,
                                                       Long appId,
                                                       CodeGenTypeEnum codeGenType) {
        if (fence == null || appId == null || codeGenType == null) {
            return Optional.empty();
        }
        ScopeState state = states.get(fence);
        if (state == null || !Objects.equals(state.appId, appId)) {
            return Optional.empty();
        }
        return Optional.of(require(fence, appId, codeGenType));
    }

    public void run(GenerationExecutionFence fence, Runnable action) {
        with(fence, () -> {
            action.run();
            return null;
        });
    }

    public <T> T with(GenerationExecutionFence fence, Supplier<T> action) {
        Objects.requireNonNull(fence, "fence");
        Objects.requireNonNull(action, "action");
        if (!states.containsKey(fence)) {
            throw new IllegalStateException("execution workspace scope is not registered");
        }
        GenerationExecutionFence previous = currentFence.get();
        currentFence.set(fence);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                currentFence.remove();
            } else {
                currentFence.set(previous);
            }
        }
    }

    public void clear(GenerationExecutionFence fence) {
        if (fence != null) {
            states.remove(fence);
            if (fence.equals(currentFence.get())) {
                currentFence.remove();
            }
        }
    }

    private static final class ScopeState {
        private final Long appId;
        private final CodeGenTypeEnum baseCodeGenType;
        private final Function<CodeGenTypeEnum, GenerationExecutionWorkspace> materializer;
        private final ConcurrentMap<CodeGenTypeEnum, GenerationExecutionWorkspace> workspaces =
                new ConcurrentHashMap<>();

        private ScopeState(Long appId,
                           CodeGenTypeEnum baseCodeGenType,
                           Function<CodeGenTypeEnum, GenerationExecutionWorkspace> materializer) {
            this.appId = appId;
            this.baseCodeGenType = baseCodeGenType;
            this.materializer = materializer;
        }
    }
}
