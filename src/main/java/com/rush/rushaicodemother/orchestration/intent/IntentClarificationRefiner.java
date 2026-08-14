package com.rush.rushaicodemother.orchestration.intent;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 在本地关键词解析出现多维歧义时，用小模型澄清一次意图。
 *
 * <p>该组件承担三件事，且刻意都不交给模型决定：</p>
 *
 * <ul>
 *   <li><b>是否调用</b>由 {@link IntentClarificationPolicy} 判定，本地已明确命中时不花钱；</li>
 *   <li><b>调用成本</b>记账到任务执行上下文的模型预算，与其他模型调用共用同一套额度；</li>
 *   <li><b>结果采纳范围</b>限定在操作类型、复杂度与文件数三个维度，
 *       破坏性风险与验证等级仍由确定性规则决定，模型无法下调安全边界。</li>
 * </ul>
 *
 * <p>澄清失败、超时或返回空结果时一律沿用本地画像：澄清是可选增益，不是必要环节。</p>
 */
@Slf4j
@Component
public class IntentClarificationRefiner {

    private static final String CLARIFICATION_STAGE = "intent_clarification_model";

    /** 模型给出的文件数估计上限，防止异常值把执行预算推向重型路径。 */
    private static final int MAX_ACCEPTED_FILE_COUNT = 60;

    private final IntentClarificationServiceFactory clarificationServiceFactory;
    private final AiModelRuntimeProperties runtimeProperties;
    private final GenerationPerformanceMonitorService performanceMonitorService;

    /**
     * 创建意图澄清精化器。
     *
     * @param clarificationServiceFactory 澄清服务工厂
     * @param runtimeProperties AI 模型运行配置
     * @param performanceMonitorService 生成性能监控服务
     */
    public IntentClarificationRefiner(IntentClarificationServiceFactory clarificationServiceFactory,
                                      AiModelRuntimeProperties runtimeProperties,
                                      GenerationPerformanceMonitorService performanceMonitorService) {
        this.clarificationServiceFactory = Objects.requireNonNull(
                clarificationServiceFactory, "意图澄清服务工厂不能为空");
        this.runtimeProperties = Objects.requireNonNull(
                runtimeProperties, "AI 模型运行配置不能为空");
        this.performanceMonitorService = Objects.requireNonNull(
                performanceMonitorService, "生成性能监控服务不能为空");
    }

    /**
     * 在需要且允许时澄清意图，并返回采纳澄清结果后的画像。
     *
     * @param profile 本地解析得到的意图画像
     * @param userMessage 用户原始需求描述
     * @param taskId 任务编号，用于观测
     * @param context 任务执行上下文，提供预算与截止时间
     * @return 澄清后的画像；无需澄清或澄清失败时返回原画像
     */
    public IntentProfile refine(IntentProfile profile,
                                String userMessage,
                                String taskId,
                                GenerationExecutionContext context) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (profile == null || context == null || StrUtil.isBlank(userMessage)) {
            return profile;
        }
        if (!canRefine(profile)) {
            return profile;
        }
        return clarifyWithModel(profile, userMessage, taskId, context);
    }

    /** 是否允许当前画像进入一次模型澄清；调用方可据此避免无效 admission gate。 */
    public boolean canRefine(IntentProfile profile) {
        return runtimeProperties.isIntentClarificationEnabled()
                && IntentClarificationPolicy.requiresClarification(profile);
    }

    /** 调用模型澄清意图，任何失败都退回本地画像。 */
    private IntentProfile clarifyWithModel(IntentProfile profile,
                                           String userMessage,
                                           String taskId,
                                           GenerationExecutionContext context) {
        GenerationPerformanceMonitorService.SpanTimer span = StrUtil.isBlank(taskId)
                ? null
                : performanceMonitorService.startSpan(
                        taskId, CLARIFICATION_STAGE, GenerationSpanCategory.MODEL);
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            context.assertCanContinue();
            Duration timeout = context.clampTimeout(runtimeProperties.getIntentClarificationTimeout());
            context.consume(GenerationBudgetKind.ROOT_MODEL_ATTEMPT);
            IntentClarificationService clarificationService = clarificationServiceFactory
                    .createExecutionIntentClarificationService(
                            timeout,
                            () -> context.consume(GenerationBudgetKind.MODEL_TURN),
                            () -> context.consume(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT));
            IntentClarification clarification = clarificationService.clarify(
                    userMessage, describeUnresolvedDimensions(profile));
            context.assertCanContinue();
            IntentProfile refined = applyClarification(profile, clarification);
            if (span != null) {
                span.close("success", describeAdoption(profile, refined));
            }
            return refined;
        } catch (GenerationExecutionPolicyException policyFailure) {
            // 预算耗尽或任务已终止属于执行策略问题，必须向上传递而不是静默降级。
            failSpan(span, policyFailure);
            throw policyFailure;
        } catch (Exception clarificationFailure) {
            // 任务本身可能已被取消，此时不应把取消掩盖成"澄清失败"。
            context.assertCanContinue();
            // 其余失败（含容量准入拒绝、模型超时、结构化输出解析失败）一律降级：
            // 澄清是可选增益，不能因为它拿不到额度就让整个生成任务失败。
            if (span != null) {
                span.close("degraded", clarificationFailure.getClass().getSimpleName());
            }
            log.warn("意图澄清失败，沿用本地画像，taskId: {}，error: {}",
                    taskId, LogExceptionSanitizer.sanitizeMessage(clarificationFailure));
            return profile;
        }
    }

    /**
     * 采纳模型澄清结果。
     *
     * <p>只覆盖本地确实未解析出的维度：本地已由关键词命中的结论比模型猜测更可靠，
     * 不接受模型推翻。同时保留原始歧义信号，便于观测澄清覆盖了哪些维度。</p>
     */
    private IntentProfile applyClarification(IntentProfile profile, IntentClarification clarification) {
        if (clarification == null) {
            return profile;
        }
        IntentAmbiguitySignal signal = profile.ambiguitySignal();
        IntentOperationType operationType = adoptOperationType(profile, clarification, signal);
        IntentSemanticComplexity complexity = adoptComplexity(profile, clarification, signal);
        int expectedFileCount = adoptFileCount(profile, clarification, signal);

        // 破坏性风险保持本地结论；验证等级按采纳后的复杂度重新推导，只允许升高不允许降低。
        IntentValidationRisk validationRisk = escalateValidationRisk(
                profile.validationRisk(), complexity);
        IntentAmbiguitySignal refinedSignal = resolveClarifiedDimensions(signal, clarification);
        return new IntentProfile(
                operationType,
                profile.affectedScopes(),
                complexity,
                profile.requiresBackend(),
                profile.requiresDatabase(),
                profile.destructiveRisk(),
                expectedFileCount,
                validationRisk,
                profile.confidence(),
                refinedSignal
        );
    }

    /** 已由模型返回有效值的维度不再保留为“未解析”，防止后续执行阶段重复付费。 */
    private IntentAmbiguitySignal resolveClarifiedDimensions(
            IntentAmbiguitySignal signal,
            IntentClarification clarification) {
        EnumSet<IntentResolutionDimension> unresolved =
                EnumSet.noneOf(IntentResolutionDimension.class);
        unresolved.addAll(signal.unresolvedDimensions());
        if (clarification.getOperationType() != null
                && clarification.getOperationType() != IntentOperationType.CREATE) {
            unresolved.remove(IntentResolutionDimension.OPERATION_TYPE);
        }
        if (clarification.getSemanticComplexity() != null) {
            unresolved.remove(IntentResolutionDimension.SEMANTIC_COMPLEXITY);
        }
        if (clarification.getExpectedFileCount() != null
                && clarification.getExpectedFileCount() > 0) {
            unresolved.remove(IntentResolutionDimension.EXPECTED_FILE_COUNT);
        }
        return new IntentAmbiguitySignal(
                Set.copyOf(unresolved), signal.scopeFallback(), signal.shortPrompt());
    }

    private IntentOperationType adoptOperationType(IntentProfile profile,
                                                   IntentClarification clarification,
                                                   IntentAmbiguitySignal signal) {
        if (clarification.getOperationType() == null
                || !signal.unresolved(IntentResolutionDimension.OPERATION_TYPE)) {
            return profile.operationType();
        }
        // CREATE 由工作区状态唯一决定，不接受模型把已有工作区的改动改判为新建。
        if (clarification.getOperationType() == IntentOperationType.CREATE) {
            return profile.operationType();
        }
        return clarification.getOperationType();
    }

    private IntentSemanticComplexity adoptComplexity(IntentProfile profile,
                                                     IntentClarification clarification,
                                                     IntentAmbiguitySignal signal) {
        if (clarification.getSemanticComplexity() == null
                || !signal.unresolved(IntentResolutionDimension.SEMANTIC_COMPLEXITY)) {
            return profile.semanticComplexity();
        }
        return clarification.getSemanticComplexity();
    }

    private int adoptFileCount(IntentProfile profile,
                               IntentClarification clarification,
                               IntentAmbiguitySignal signal) {
        Integer clarified = clarification.getExpectedFileCount();
        if (clarified == null
                || !signal.unresolved(IntentResolutionDimension.EXPECTED_FILE_COUNT)) {
            return profile.expectedFileCount();
        }
        if (clarified <= 0) {
            return profile.expectedFileCount();
        }
        return Math.min(clarified, MAX_ACCEPTED_FILE_COUNT);
    }

    /** 复杂度升高时同步抬高验证等级，避免澄清结果只放大预算却不加强校验。 */
    private IntentValidationRisk escalateValidationRisk(IntentValidationRisk currentRisk,
                                                        IntentSemanticComplexity complexity) {
        if (complexity != IntentSemanticComplexity.HIGH) {
            return currentRisk;
        }
        return IntentValidationRisk.HIGH;
    }

    /** 描述未解析维度，作为模型输入的一部分。 */
    private String describeUnresolvedDimensions(IntentProfile profile) {
        return profile.ambiguitySignal().unresolvedDimensions().stream()
                .map(IntentResolutionDimension::description)
                .sorted()
                .collect(Collectors.joining("、"));
    }

    /** 描述澄清实际改动了哪些维度，仅用于观测。 */
    private String describeAdoption(IntentProfile before, IntentProfile after) {
        return String.format(
                Locale.ROOT,
                "operation=%s->%s,complexity=%s->%s,files=%d->%d",
                before.operationType(), after.operationType(),
                before.semanticComplexity(), after.semanticComplexity(),
                before.expectedFileCount(), after.expectedFileCount());
    }

    private void failSpan(GenerationPerformanceMonitorService.SpanTimer span,
                          RuntimeException failure) {
        if (span != null) {
            span.failed(failure.getClass().getSimpleName());
        }
    }
}
