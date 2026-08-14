package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.decision.GenerationMutability;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;

import java.util.Objects;

/** 标识提交前即可判定的一类静态生成能力。 */
public record GenerationPipelineCapabilityKey(
        IntentOperationType operation,
        GenerationMutability mutability,
        CodeGenTypeEnum targetType
) {

    public GenerationPipelineCapabilityKey {
        Objects.requireNonNull(operation, "能力操作类型不能为空");
        Objects.requireNonNull(mutability, "能力可变性不能为空");
        Objects.requireNonNull(targetType, "能力工程类型不能为空");
    }

    public static GenerationPipelineCapabilityKey from(GenerationScenarioDecision decision) {
        Objects.requireNonNull(decision, "场景决策不能为空");
        return new GenerationPipelineCapabilityKey(
                decision.operation(), decision.mutability(), decision.targetType());
    }
}
