package com.rush.rushaicodemother.orchestration.intent;

import java.util.Set;

/**
 * 对用户自然语言进行一次结构化解析后得到的不可变意图画像。
 *
 * <p>画像只保存有限枚举、布尔值和数值，不保存原始提示词，便于在路由、
 * 执行计划与观测链路之间安全复用。</p>
 */
public record IntentProfile(
        IntentOperationType operationType,
        Set<IntentAffectedScope> affectedScopes,
        IntentSemanticComplexity semanticComplexity,
        boolean requiresBackend,
        boolean requiresDatabase,
        IntentDestructiveRisk destructiveRisk,
        int expectedFileCount,
        IntentValidationRisk validationRisk,
        double confidence
) {

    public IntentProfile {
        operationType = operationType == null ? IntentOperationType.EDIT : operationType;
        affectedScopes = affectedScopes == null || affectedScopes.isEmpty()
                ? Set.of(IntentAffectedScope.UNKNOWN)
                : Set.copyOf(affectedScopes);
        semanticComplexity = semanticComplexity == null
                ? IntentSemanticComplexity.MEDIUM : semanticComplexity;
        destructiveRisk = destructiveRisk == null ? IntentDestructiveRisk.LOW : destructiveRisk;
        expectedFileCount = Math.max(0, expectedFileCount);
        validationRisk = validationRisk == null ? IntentValidationRisk.MEDIUM : validationRisk;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    /** 返回兼容旧任务命令的保守画像。 */
    public static IntentProfile unknown() {
        return new IntentProfile(
                IntentOperationType.EDIT,
                Set.of(IntentAffectedScope.UNKNOWN),
                IntentSemanticComplexity.MEDIUM,
                false,
                false,
                IntentDestructiveRisk.LOW,
                0,
                IntentValidationRisk.MEDIUM,
                0.0
        );
    }
}
