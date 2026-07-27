package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * 由生成任务运行时控制的有限资源。
 *
 * <p>枚举有意与基础设施无关，因此新的执行者可以加入相同的
 * 预算协议不依赖于具体的管道实现。</p>
 */
public enum GenerationBudgetKind {

    ROOT_MODEL_ATTEMPT("根模型尝试次数"),
    MODEL_TURN("模型对话回合数"),
    PROVIDER_FAILOVER_ATTEMPT("模型提供方故障转移次数"),
    TOOL_WRITE("文件变更操作次数"),
    BUILD_EXECUTION("构建执行次数"),
    REPAIR_ROUND("自动修复轮次");

    private final String displayName;

    GenerationBudgetKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** 兼容旧任务快照中的 MODEL_ATTEMPT，并将其收敛为根模型尝试预算。 */
    @JsonCreator
    public static GenerationBudgetKind fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("生成预算类型不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("MODEL_ATTEMPT".equals(normalized)) {
            return ROOT_MODEL_ATTEMPT;
        }
        return GenerationBudgetKind.valueOf(normalized);
    }
}
