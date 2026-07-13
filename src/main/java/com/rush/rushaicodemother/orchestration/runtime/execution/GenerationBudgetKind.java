package com.rush.rushaicodemother.orchestration.runtime.execution;

/**
 * Finite resources controlled by a generation task runtime.
 *
 * <p>The enum is intentionally infrastructure-neutral so new executors can join the same
 * budget protocol without depending on a concrete pipeline implementation.</p>
 */
public enum GenerationBudgetKind {

    MODEL_ATTEMPT("模型调用次数"),
    TOOL_WRITE("写工具调用次数"),
    BUILD_EXECUTION("构建执行次数"),
    REPAIR_ROUND("自动修复轮次");

    private final String displayName;

    GenerationBudgetKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
