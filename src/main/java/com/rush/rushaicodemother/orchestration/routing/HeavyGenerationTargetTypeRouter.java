package com.rush.rushaicodemother.orchestration.routing;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.AiCodeGenTypeRoutingService;
import com.rush.rushaicodemother.ai.AiCodeGenTypeRoutingServiceFactory;
import com.rush.rushaicodemother.ai.intent.BackendIntentDetector;
import com.rush.rushaicodemother.ai.intent.DeterministicCodeGenTypeRouter;
import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 解析 HEAVY 生成的目标工程类型。高置信意图走本地规则，只有模糊意图才占用模型预算。
 */
@Slf4j
@Component
public class HeavyGenerationTargetTypeRouter {

    private static final String LOCAL_ROUTING_STAGE = "heavy_intent_routing_local";
    private static final String MODEL_ROUTING_STAGE = "heavy_intent_routing_model";

    private final AiCodeGenTypeRoutingServiceFactory routingServiceFactory;
    private final BackendIntentDetector backendIntentDetector;
    private final DeterministicCodeGenTypeRouter deterministicRouter;
    private final AiModelRuntimeProperties runtimeProperties;
    private final GenerationExecutionContextService executionContextService;
    private final GenerationPerformanceMonitorService performanceMonitorService;

    /**
 * 创建重型生成目标类型{@code Router}实例并完成必要的依赖和初始状态设置。
 *
 * @param routingServiceFactory 路由服务工厂
 * @param backendIntentDetector {@code backendIntentDetector} 对应的调用参数
 * @param deterministicRouter {@code deterministicRouter} 对应的调用参数
 * @param runtimeProperties 运行时属性
 * @param executionContextService 执行上下文服务
 * @param performanceMonitorService 处理该职责的领域服务
 */
    public HeavyGenerationTargetTypeRouter(
            AiCodeGenTypeRoutingServiceFactory routingServiceFactory,
            BackendIntentDetector backendIntentDetector,
            DeterministicCodeGenTypeRouter deterministicRouter,
            AiModelRuntimeProperties runtimeProperties,
            GenerationExecutionContextService executionContextService,
            GenerationPerformanceMonitorService performanceMonitorService
    ) {
        this.routingServiceFactory = Objects.requireNonNull(
                routingServiceFactory, "AI 类型路由服务工厂不能为空");
        this.backendIntentDetector = Objects.requireNonNull(
                backendIntentDetector, "后端意图检测器不能为空");
        this.deterministicRouter = Objects.requireNonNull(
                deterministicRouter, "本地类型路由器不能为空");
        this.runtimeProperties = Objects.requireNonNull(
                runtimeProperties, "AI 模型运行配置不能为空");
        this.executionContextService = Objects.requireNonNull(
                executionContextService, "生成执行上下文服务不能为空");
        this.performanceMonitorService = Objects.requireNonNull(
                performanceMonitorService, "生成性能监控服务不能为空");
    }

    /**
 * 根据当前上下文解析重型生成目标类型{@code Router}。
 *
 * @param taskId 任务编号
 * @param appId 应用编号
 * @param userMessage 用户消息
 * @param currentType 当前类型
 * @param hasGeneratedCode {@code hasGeneratedCode} 对应的调用参数
 * @return 重型生成目标类型{@code Router}
 */
    public CodeGenTypeEnum resolve(String taskId,
                                   Long appId,
                                   String userMessage,
                                   CodeGenTypeEnum currentType,
                                   boolean hasGeneratedCode) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (currentType == null) {
            throw new IllegalArgumentException("当前代码生成类型不能为空");
        }
        String routingPrompt = StrUtil.blankToDefault(userMessage, "");
        BackendIntentDetector.BackendIntentResult intent =
                backendIntentDetector.detectIntent(routingPrompt);
        if (intent == null) {
            throw new IllegalStateException("后端意图检测未返回结果");
        }

        if (runtimeProperties.isLocalFirstHeavyRoutingEnabled()) {
            long startedAt = System.nanoTime();
            if (currentType == CodeGenTypeEnum.FULL_STACK_PROJECT) {
                recordLocalRoute(taskId, intent, currentType, startedAt);
                return currentType;
            }
            Optional<CodeGenTypeEnum> explicitRoute =
                    deterministicRouter.routeExplicit(routingPrompt, intent);
            if (explicitRoute.isPresent()) {
                CodeGenTypeEnum targetType = preserveExistingCapabilities(
                        currentType, explicitRoute.get(), hasGeneratedCode);
                recordLocalRoute(taskId, intent, targetType, startedAt);
                return targetType;
            }
            if (intent.level() == BackendIntentDetector.BackendIntentResult.IntentLevel.NONE) {
                recordLocalRoute(taskId, intent, currentType, startedAt);
                return currentType;
            }
        }
        return routeWithModel(taskId, appId, routingPrompt, currentType, hasGeneratedCode, intent);
    }

    /** 为并模型选择处理路由。 */
    private CodeGenTypeEnum routeWithModel(
            String taskId,
            Long appId,
            String routingPrompt,
            CodeGenTypeEnum currentType,
            boolean hasGeneratedCode,
            BackendIntentDetector.BackendIntentResult intent
    ) {
        GenerationExecutionContext context = resolveExecutionContext(taskId);
        GenerationPerformanceMonitorService.SpanTimer span = StrUtil.isBlank(taskId)
                ? null
                : performanceMonitorService.startSpan(
                        taskId, MODEL_ROUTING_STAGE, GenerationSpanCategory.MODEL);
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            AiCodeGenTypeRoutingService routingService;
            if (context == null) {
                routingService = routingServiceFactory.createAiCodeGenTypeRoutingService();
            } else {
                context.assertCanContinue();
                Duration timeout = context.clampTimeout(context.limits().modelCallTimeout());
                context.consume(GenerationBudgetKind.ROOT_MODEL_ATTEMPT);
                routingService = routingServiceFactory.createExecutionAiCodeGenTypeRoutingService(
                        timeout,
                        () -> context.consume(GenerationBudgetKind.MODEL_TURN),
                        () -> context.consume(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT)
                );
            }
            CodeGenTypeEnum routedType = routingService.routeCodeGenType(routingPrompt);
            if (context != null) {
                context.assertCanContinue();
            }
            CodeGenTypeEnum constrainedType = backendIntentDetector.constrainCodeGenType(
                    intent,
                    routedType == null ? currentType : routedType
            );
            CodeGenTypeEnum targetType = preserveExistingCapabilities(
                    currentType,
                    constrainedType == null ? currentType : constrainedType,
                    hasGeneratedCode
            );
            if (span != null) {
                span.success();
            }
            return targetType;
        } catch (GenerationExecutionPolicyException policyFailure) {
            failSpan(span, policyFailure);
            throw policyFailure;
        } catch (BusinessException businessFailure) {
            failSpan(span, businessFailure);
            throw businessFailure;
        } catch (Exception routingFailure) {
            if (context != null) {
                context.assertCanContinue();
            }
            if (span != null) {
                span.close("degraded", routingFailure.getClass().getSimpleName());
            }
            log.warn("HEAVY 目标类型模型路由失败，沿用当前类型，appId: {}，error: {}",
                    appId, LogExceptionSanitizer.sanitizeMessage(routingFailure));
            return currentType;
        }
    }

    private GenerationExecutionContext resolveExecutionContext(String taskId) {
        if (StrUtil.isBlank(taskId)) {
            return null;
        }
        return executionContextService.getByTaskId(taskId)
                .orElseThrow(() -> new GenerationExecutionPolicyException(
                        "HEAVY 类型路由没有活动的任务执行上下文，taskId=" + taskId));
    }

    /** 返回{@code preserve}{@code Existing}能力。 */
    private CodeGenTypeEnum preserveExistingCapabilities(CodeGenTypeEnum currentType,
                                                         CodeGenTypeEnum requestedType,
                                                         boolean hasGeneratedCode) {
        if (!hasGeneratedCode || currentType == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return hasGeneratedCode
                    ? currentType
                    : CodeGenTypeEnum.max(currentType, requestedType);
        }
        if (crossesFrontendBackendBoundary(currentType, requestedType)) {
            return CodeGenTypeEnum.FULL_STACK_PROJECT;
        }
        return CodeGenTypeEnum.max(currentType, requestedType);
    }

    private boolean crossesFrontendBackendBoundary(CodeGenTypeEnum currentType,
                                                    CodeGenTypeEnum requestedType) {
        return (isFrontend(currentType) && requestedType == CodeGenTypeEnum.BACKEND_PROJECT)
                || (currentType == CodeGenTypeEnum.BACKEND_PROJECT && isFrontend(requestedType));
    }

    private boolean isFrontend(CodeGenTypeEnum type) {
        return type == CodeGenTypeEnum.HTML
                || type == CodeGenTypeEnum.MULTI_FILE
                || type == CodeGenTypeEnum.VUE_PROJECT;
    }

    /** 记录{@code Local}{@code Route}相关指标或状态。 */
    private void recordLocalRoute(
            String taskId,
            BackendIntentDetector.BackendIntentResult intent,
            CodeGenTypeEnum targetType,
            long startedAt
    ) {
        if (StrUtil.isBlank(taskId)) {
            return;
        }
        String detail = "intent=" + intent.level().name().toLowerCase(Locale.ROOT)
                + ",target=" + targetType.getValue();
        performanceMonitorService.recordSpan(
                taskId,
                LOCAL_ROUTING_STAGE,
                GenerationSpanCategory.PIPELINE,
                "success",
                Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAt)),
                detail
        );
    }

    private void failSpan(GenerationPerformanceMonitorService.SpanTimer span,
                          RuntimeException failure) {
        if (span != null) {
            span.failed(failure.getClass().getSimpleName());
        }
    }
}
