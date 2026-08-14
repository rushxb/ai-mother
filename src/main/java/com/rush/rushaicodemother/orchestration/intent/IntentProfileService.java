package com.rush.rushaicodemother.orchestration.intent;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 将用户提示词与工作区状态解析为可复用的结构化意图画像。
 *
 * <p>解析过程完全本地、确定且无副作用；为限制异常超长输入的分析成本，
 * 只分析提示词首尾各一段内容，画像中不会保存原始提示词。</p>
 */
@Service
public class IntentProfileService {

    private static final int MAX_ANALYZED_CHARACTERS = 20_000;
    private static final int ANALYZED_EDGE_CHARACTERS = MAX_ANALYZED_CHARACTERS / 2;
    private static final IntentLexicalRuleSet LEXICAL_RULES = IntentLexicalRuleSet.defaultRules();

    /**
     * 返回本次本地意图判定使用的词法规则版本。
     *
     * <p>后续持久化场景决策时可直接记录该值，以保证历史路由可回放。</p>
     */
    public String lexicalRuleVersion() {
        return LEXICAL_RULES.version();
    }

    public IntentProfile analyze(GenerationTaskRequest request,
                                 CodeGenTypeEnum codeGenType,
                                 GenerationWorkspace workspace) {
        String normalizedMessage = normalizeForAnalysis(request == null ? null : request.message());
        if (normalizedMessage.isBlank()) {
            return IntentProfile.unknown();
        }

        boolean firstGeneration = workspace != null && !workspace.exists();
        // 解析过程同步记录每个维度是"关键词命中"还是"走了兜底默认值"，
        // 供上层判断是否值得为澄清意图额外付费。
        ResolutionRecorder recorder = new ResolutionRecorder(normalizedMessage);
        Set<IntentAffectedScope> scopes = detectScopes(normalizedMessage, codeGenType, recorder);
        boolean requiresDatabase = scopes.contains(IntentAffectedScope.DATABASE);
        boolean requiresBackend = requiresDatabase
                || scopes.contains(IntentAffectedScope.BACKEND)
                || scopes.contains(IntentAffectedScope.API)
                || scopes.contains(IntentAffectedScope.AUTHENTICATION);
        IntentOperationType operationType = detectOperationType(
                normalizedMessage, firstGeneration, recorder);
        IntentDestructiveRisk destructiveRisk = detectDestructiveRisk(normalizedMessage);
        int expectedFileCount = estimateFileCount(
                normalizedMessage, firstGeneration, scopes, requiresBackend, requiresDatabase, recorder);
        IntentSemanticComplexity complexity = detectComplexity(
                normalizedMessage, firstGeneration, scopes, requiresBackend, requiresDatabase,
                destructiveRisk, expectedFileCount, recorder);
        IntentValidationRisk validationRisk = detectValidationRisk(
                operationType, scopes, complexity, destructiveRisk, requiresBackend, requiresDatabase);
        double confidence = calculateConfidence(
                normalizedMessage, firstGeneration, scopes, operationType, complexity);

        return new IntentProfile(
                operationType,
                scopes,
                complexity,
                requiresBackend,
                requiresDatabase,
                destructiveRisk,
                expectedFileCount,
                validationRisk,
                confidence,
                recorder.toSignal()
        );
    }

    /**
     * 在一次解析过程中累积"哪些维度只能依赖兜底默认值"的事实。
     *
     * <p>只在检测方法真正走到兜底分支时登记，避免歧义信号退化为又一套关键词猜测。</p>
     */
    private static final class ResolutionRecorder {

        private final EnumSet<IntentResolutionDimension> unresolved =
                EnumSet.noneOf(IntentResolutionDimension.class);
        private final boolean shortPrompt;
        private boolean scopeFallback;

        private ResolutionRecorder(String normalizedMessage) {
            this.shortPrompt = normalizedMessage.length() < IntentAmbiguitySignal.SHORT_PROMPT_THRESHOLD;
        }

        private void markUnresolved(IntentResolutionDimension dimension) {
            unresolved.add(dimension);
        }

        private void markScopeFallback() {
            scopeFallback = true;
            unresolved.add(IntentResolutionDimension.AFFECTED_SCOPE);
        }

        private IntentAmbiguitySignal toSignal() {
            return new IntentAmbiguitySignal(unresolved, scopeFallback, shortPrompt);
        }
    }

    private IntentOperationType detectOperationType(String message,
                                                    boolean firstGeneration,
                                                    ResolutionRecorder recorder) {
        // 空工作区是客观事实而非推断，因此首次生成不算歧义。
        if (firstGeneration) {
            return IntentOperationType.CREATE;
        }
        boolean repairAction = matches(message, IntentLexicalFeature.REPAIR_ACTION);
        boolean repairSymptom = matches(message, IntentLexicalFeature.REPAIR_SYMPTOM);
        boolean explanationAction = matches(message, IntentLexicalFeature.EXPLANATION_ACTION);
        boolean editAction = matches(message, IntentLexicalFeature.EDIT_ACTION);

        // 显式动作的优先级高于背景症状：“解释 error”是只读分析，不是修复任务。
        if (repairAction) {
            return IntentOperationType.REPAIR;
        }
        if (explanationAction && !editAction) {
            return IntentOperationType.EXPLAIN;
        }
        if (repairSymptom) {
            return IntentOperationType.REPAIR;
        }
        if (!editAction) {
            // 未出现任何显式动作词，EDIT 只是兜底猜测。
            recorder.markUnresolved(IntentResolutionDimension.OPERATION_TYPE);
        }
        return IntentOperationType.EDIT;
    }

    private Set<IntentAffectedScope> detectScopes(String message,
                                                  CodeGenTypeEnum codeGenType,
                                                  ResolutionRecorder recorder) {
        EnumSet<IntentAffectedScope> scopes = EnumSet.noneOf(IntentAffectedScope.class);
        addScopeWhenMatched(scopes, IntentAffectedScope.FRONTEND, message, IntentLexicalFeature.FRONTEND);
        addScopeWhenMatched(scopes, IntentAffectedScope.BACKEND, message, IntentLexicalFeature.BACKEND);
        addScopeWhenMatched(scopes, IntentAffectedScope.API, message, IntentLexicalFeature.API);
        addScopeWhenMatched(scopes, IntentAffectedScope.DATABASE, message, IntentLexicalFeature.DATABASE);
        addScopeWhenMatched(scopes, IntentAffectedScope.AUTHENTICATION, message, IntentLexicalFeature.AUTHENTICATION);
        addScopeWhenMatched(scopes, IntentAffectedScope.BUILD_CONFIGURATION, message,
                IntentLexicalFeature.BUILD_CONFIGURATION);
        addScopeWhenMatched(scopes, IntentAffectedScope.INFRASTRUCTURE, message,
                IntentLexicalFeature.INFRASTRUCTURE);
        addScopeWhenMatched(scopes, IntentAffectedScope.TESTING, message, IntentLexicalFeature.TESTING);
        addScopeWhenMatched(scopes, IntentAffectedScope.DOCUMENTATION, message,
                IntentLexicalFeature.DOCUMENTATION);

        if (scopes.isEmpty() && codeGenType != null && codeGenType != CodeGenTypeEnum.HTML) {
            // 未命中任何领域关键词，只能按工程类型假定为前端改动。
            recorder.markScopeFallback();
            scopes.add(IntentAffectedScope.FRONTEND);
        }
        if (scopes.isEmpty()) {
            recorder.markScopeFallback();
            scopes.add(IntentAffectedScope.UNKNOWN);
        }
        return Set.copyOf(scopes);
    }

    private void addScopeWhenMatched(Set<IntentAffectedScope> scopes,
                                     IntentAffectedScope scope,
                                     String message,
                                     IntentLexicalFeature feature) {
        if (matches(message, feature)) {
            scopes.add(scope);
        }
    }

    private IntentDestructiveRisk detectDestructiveRisk(String message) {
        if (matches(message, IntentLexicalFeature.HIGH_DESTRUCTIVE_RISK)) {
            return IntentDestructiveRisk.HIGH;
        }
        if (matches(message, IntentLexicalFeature.MEDIUM_DESTRUCTIVE_RISK)) {
            return IntentDestructiveRisk.MEDIUM;
        }
        return IntentDestructiveRisk.LOW;
    }

    private int estimateFileCount(String message,
                                  boolean firstGeneration,
                                  Set<IntentAffectedScope> scopes,
                                  boolean requiresBackend,
                                  boolean requiresDatabase,
                                  ResolutionRecorder recorder) {
        if (matches(message, IntentLexicalFeature.SINGLE_FILE)) {
            return 1;
        }
        if (firstGeneration) {
            return requiresBackend || requiresDatabase ? 12 : 6;
        }
        if (matches(message, IntentLexicalFeature.MULTI_FILE)) {
            return Math.max(4, scopes.size() + 2);
        }
        if (requiresBackend && requiresDatabase) {
            return 6;
        }
        if (requiresBackend) {
            return 4;
        }
        if (matches(message, IntentLexicalFeature.LIGHT_EDIT)) {
            return 1;
        }
        // 既无规模关键词也无轻量特征，只能按影响范围数量粗估。
        recorder.markUnresolved(IntentResolutionDimension.EXPECTED_FILE_COUNT);
        return Math.max(2, scopes.size());
    }

    private IntentSemanticComplexity detectComplexity(String message,
                                                       boolean firstGeneration,
                                                       Set<IntentAffectedScope> scopes,
                                                       boolean requiresBackend,
                                                       boolean requiresDatabase,
                                                       IntentDestructiveRisk destructiveRisk,
                                                       int expectedFileCount,
                                                       ResolutionRecorder recorder) {
        if (matches(message, IntentLexicalFeature.HIGH_COMPLEXITY)
                || destructiveRisk == IntentDestructiveRisk.HIGH
                || scopes.contains(IntentAffectedScope.INFRASTRUCTURE)
                || (requiresBackend && requiresDatabase
                && scopes.contains(IntentAffectedScope.AUTHENTICATION))) {
            return IntentSemanticComplexity.HIGH;
        }
        if (!firstGeneration
                && expectedFileCount <= 2
                && !requiresBackend
                && !requiresDatabase
                && destructiveRisk == IntentDestructiveRisk.LOW
                && matches(message, IntentLexicalFeature.LIGHT_EDIT)) {
            return IntentSemanticComplexity.LOW;
        }
        // 首次生成的中等档由工作区状态决定，属于确定结论；其余情况是兜底默认值。
        if (!firstGeneration) {
            recorder.markUnresolved(IntentResolutionDimension.SEMANTIC_COMPLEXITY);
        }
        return IntentSemanticComplexity.MEDIUM;
    }

    private IntentValidationRisk detectValidationRisk(IntentOperationType operationType,
                                                       Set<IntentAffectedScope> scopes,
                                                       IntentSemanticComplexity complexity,
                                                       IntentDestructiveRisk destructiveRisk,
                                                       boolean requiresBackend,
                                                       boolean requiresDatabase) {
        if (complexity == IntentSemanticComplexity.HIGH
                || destructiveRisk == IntentDestructiveRisk.HIGH
                || (requiresBackend && requiresDatabase)
                || scopes.contains(IntentAffectedScope.AUTHENTICATION)
                || scopes.contains(IntentAffectedScope.INFRASTRUCTURE)) {
            return IntentValidationRisk.HIGH;
        }
        if (operationType == IntentOperationType.REPAIR
                || requiresBackend
                || requiresDatabase
                || scopes.contains(IntentAffectedScope.API)
                || scopes.contains(IntentAffectedScope.BUILD_CONFIGURATION)
                || scopes.contains(IntentAffectedScope.TESTING)) {
            return IntentValidationRisk.MEDIUM;
        }
        return IntentValidationRisk.LOW;
    }

    private double calculateConfidence(String message,
                                       boolean firstGeneration,
                                       Set<IntentAffectedScope> scopes,
                                       IntentOperationType operationType,
                                       IntentSemanticComplexity complexity) {
        double confidence = firstGeneration ? 0.92 : 0.72;
        if (!scopes.contains(IntentAffectedScope.UNKNOWN)) {
            confidence += 0.08;
        }
        if (operationType == IntentOperationType.REPAIR || operationType == IntentOperationType.EXPLAIN) {
            confidence += 0.07;
        }
        if (complexity == IntentSemanticComplexity.LOW || complexity == IntentSemanticComplexity.HIGH) {
            confidence += 0.05;
        }
        if (message.length() < 6) {
            confidence -= 0.25;
        }
        return Math.max(0.0, Math.min(0.99, confidence));
    }

    private String normalizeForAnalysis(String message) {
        String normalized = StrUtil.blankToDefault(message, "").trim().toLowerCase(Locale.ROOT);
        if (normalized.length() <= MAX_ANALYZED_CHARACTERS) {
            return normalized;
        }
        return normalized.substring(0, ANALYZED_EDGE_CHARACTERS)
                + " "
                + normalized.substring(normalized.length() - ANALYZED_EDGE_CHARACTERS);
    }

    private boolean matches(String message, IntentLexicalFeature feature) {
        return LEXICAL_RULES.matches(message, feature);
    }
}
