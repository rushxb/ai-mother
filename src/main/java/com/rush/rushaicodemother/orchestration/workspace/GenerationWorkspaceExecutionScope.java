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
 * 跨同步生成边界传播执行拥有的工作空间。
 *
 * <p> 注册表由完整的栅栏键控，而不仅仅是任务 ID。老工人因此保留
 * 即使在新工人声明了相同的持久任务之后，仍能解决自己的纪元。</p>
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
