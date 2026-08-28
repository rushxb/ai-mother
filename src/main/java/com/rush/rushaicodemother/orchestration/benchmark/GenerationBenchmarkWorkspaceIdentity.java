package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.util.Objects;

/**
 * 跨版本工作区快照的逻辑身份。
 *
 * <p>物理根目录会随执行 epoch 和发布 candidate 改变；评分只能在同一应用、
 * 且当前制品类型等于基线声明目标类型时比较相对路径摘要。</p>
 */
public record GenerationBenchmarkWorkspaceIdentity(
        Long appId,
        CodeGenTypeEnum capturedType,
        CodeGenTypeEnum expectedTargetType
) {

    public GenerationBenchmarkWorkspaceIdentity {
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("Benchmark 工作区应用身份无效");
        }
        if (capturedType == null || expectedTargetType == null) {
            throw new IllegalArgumentException("Benchmark 工作区工程类型身份不能为空");
        }
        if (capturedType != expectedTargetType
                && !capturedType.canUpgradeTo(expectedTargetType)) {
            throw new IllegalArgumentException("Benchmark 工作区目标类型不是合法升级");
        }
    }

    /** 当前制品身份必须闭合基线声明的目标类型，不允许跨应用或继续隐式迁移。 */
    public boolean accepts(GenerationBenchmarkWorkspaceIdentity current) {
        return current != null
                && Objects.equals(appId, current.appId())
                && current.capturedType() == expectedTargetType
                && current.expectedTargetType() == current.capturedType();
    }
}
