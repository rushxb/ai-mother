package com.rush.rushaicodemother.orchestration.intent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.util.Set;

/**
 * 对用户自然语言进行一次结构化解析后得到的不可变意图画像。
 *
 * <p>画像只保存有限枚举、布尔值和数值，不保存原始提示词，便于在路由、
 * 执行计划与观测链路之间安全复用。{@code explicitProjectType} 只在提交前把明确的
 * 工程迁移诉求交给场景内核；持久命令由场景决策保存最终目标，因此该提示不扩展
 * 跨版本任务协议。</p>
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
        double confidence,
        IntentAmbiguitySignal ambiguitySignal,
        @JsonIgnore
        CodeGenTypeEnum explicitProjectType
) {

    public IntentProfile {
        ambiguitySignal = ambiguitySignal == null
                ? IntentAmbiguitySignal.resolved()
                : ambiguitySignal;
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

    /**
     * 构造未携带歧义信号的画像。
     *
     * <p>供旧任务命令反序列化与既有调用方使用：缺失信号时按"本地解析确定"处理，
     * 避免历史任务在恢复后被误判为需要澄清。</p>
     */
    public IntentProfile(IntentOperationType operationType,
                         Set<IntentAffectedScope> affectedScopes,
                         IntentSemanticComplexity semanticComplexity,
                         boolean requiresBackend,
                         boolean requiresDatabase,
                         IntentDestructiveRisk destructiveRisk,
                         int expectedFileCount,
                         IntentValidationRisk validationRisk,
                         double confidence) {
        this(operationType, affectedScopes, semanticComplexity, requiresBackend, requiresDatabase,
                destructiveRisk, expectedFileCount, validationRisk, confidence,
                IntentAmbiguitySignal.resolved(), null);
    }

    /** 兼容尚未携带显式工程类型的历史调用方与持久任务。 */
    public IntentProfile(IntentOperationType operationType,
                         Set<IntentAffectedScope> affectedScopes,
                         IntentSemanticComplexity semanticComplexity,
                         boolean requiresBackend,
                         boolean requiresDatabase,
                         IntentDestructiveRisk destructiveRisk,
                         int expectedFileCount,
                         IntentValidationRisk validationRisk,
                         double confidence,
                         IntentAmbiguitySignal ambiguitySignal) {
        this(operationType, affectedScopes, semanticComplexity, requiresBackend, requiresDatabase,
                destructiveRisk, expectedFileCount, validationRisk, confidence,
                ambiguitySignal, null);
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
                0.0,
                IntentAmbiguitySignal.unresolved(),
                null
        );
    }

    /** 在保留其余结论的前提下替换歧义信号。 */
    public IntentProfile withAmbiguitySignal(IntentAmbiguitySignal signal) {
        return new IntentProfile(
                operationType, affectedScopes, semanticComplexity, requiresBackend, requiresDatabase,
                destructiveRisk, expectedFileCount, validationRisk, confidence, signal,
                explicitProjectType);
    }
}
