package com.rush.rushaicodemother.orchestration.pipeline;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTerminalOutcome;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.create.CreatePostGenerationValidationService;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import com.rush.rushaicodemother.orchestration.template.SlotFillGenerationService;
import com.rush.rushaicodemother.orchestration.template.SlotFillResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插槽填充生成处理流水线。
 */
@Slf4j
@Order(20)
@Component
@RequiredArgsConstructor
public class SlotFillGenerationPipeline implements GenerationPipeline {

    private static final String CREATE_FAILURE_MESSAGE = "CREATE 模板生成失败，请稍后重试";
    private static final String CREATE_FAILURE_REASON = "create_generation_failed";
    private static final String CREATE_VALIDATION_FAILURE_REASON = "create_validation_failed";
    private static final String CREATE_DEADLINE_REASON = "create_generation_deadline_exceeded";
    private static final String CREATE_CANCELLED_REASON = "user_requested";

    private final GenerationTaskLifecycleService generationTaskLifecycleService;
    private final GenerationPerformanceMonitorService generationPerformanceMonitorService;
    private final CreatePostGenerationValidationService createPostGenerationValidationService;
    private final GenerationEventPublisher generationEventPublisher;
    private final SlotFillGenerationService slotFillGenerationService;

    @Override
    public String route() {
        return GenerationRoute.CREATE;
    }

    /**
 * 返回{@code supports}。
 *
 * @param request 请求参数
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean supports(GenerationPipelineRequest request) {
        CodeGenTypeEnum type = request.codeGenType();
        return request.modeIs(GenerationMode.CREATE)
                && (type == CodeGenTypeEnum.VUE_PROJECT
                || type == CodeGenTypeEnum.BACKEND_PROJECT
                || type == CodeGenTypeEnum.FULL_STACK_PROJECT)
                && request.workspace() != null
                && !request.workspace().exists();
    }

    /**
 * 执行插槽填充生成流水线处理流程。
 *
 * @param request 请求参数
 * @return 插槽填充生成流水线
 */
    @Override
    public GenerationPipelineOutcome execute(GenerationPipelineRequest request) {
        GenerationTaskExecution execution = request.requireExecution();
        App app = request.taskRequest().app();
        GenerationSession session = execution.session();
        String taskId = execution.taskId();
        Instant startedAt = Instant.now();
        boolean lifecycleStarted = false;
        try {
            session.throwIfCancelled();
            generationTaskLifecycleService.startOrTransitionGeneration(
                    taskId,
                    app.getId(),
                    request.taskRequest().loginUser().getId(),
                    request.codeGenType(),
                    request.codeGenType(),
                    request.taskRequest().message(),
                    request.taskRequest().message(),
                    true,
                    "create",
                    route(),
                    AppConstant.GENERATING_STAGE_CREATE
            );
            lifecycleStarted = true;
            generationTaskLifecycleService.updateGenerationStage(
                    taskId,
                    app.getId(),
                    AppConstant.GENERATING_STAGE_CREATE,
                    "正在生成 CREATE 模板项目..."
            );
            generationPerformanceMonitorService.startTask(
                    taskId,
                    app.getId(),
                    request.taskRequest().loginUser().getId(),
                    route(),
                    request.codeGenType().getValue(),
                    startedAt,
                    request.modeDecision()
            );
            generationEventPublisher.publishSafely(
                    request.taskRequest(), GenerationEventType.TASK_ROUTE, "使用 CREATE 模板生成路径", Map.of(
                            "taskId", taskId,
                            "route", route(),
                            "mode", request.modeDecision().mode().name(),
                            "routerReason", request.modeDecision().reason(),
                            "routingDecisionCode", request.modeDecision().decisionCode().name()
                    ));
            session.emit(GenerationStreamEvent.generationStage(
                    "正在生成 CREATE 模板项目...",
                    Map.of("stage", AppConstant.GENERATING_STAGE_CREATE,
                            "taskId", taskId, "route", route())
            ));
            return runCreateGeneration(request, taskId, startedAt, session);
        } catch (RuntimeException startFailure) {
            log.error("CREATE 模板路径执行失败，appId: {}, taskId: {}",
                    app.getId(), taskId, LogExceptionSanitizer.sanitize(startFailure));
            return finishInitializationFailure(
                    request, taskId, session, lifecycleStarted, startFailure);
        }
    }

    /** 运行创建生成处理流程。 */
    private GenerationPipelineOutcome runCreateGeneration(GenerationPipelineRequest request,
                                                           String taskId,
                                                           Instant startedAt,
                                                           GenerationSession session) {
        App app = request.taskRequest().app();
        int initialWorkspaceMutations = successfulWorkspaceMutationCount(session);
        SlotFillResult result = null;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            result = slotFillGenerationService.tryGenerate(app, request.taskRequest(), session);
            session.throwIfCancelled();
            if (result == null) {
                String diagnosticReason = StrUtil.blankToDefault(
                        slotFillGenerationService.consumeLastFailureReason(),
                        "CREATE recipe 运行时未产生可写入 patch，请检查模板 recipe、spec 归一化或本地渲染结果"
                );
                log.warn("CREATE 模板生成失败，请稍后重试appId: {}, reason: {}", app.getId(),
                        LogExceptionSanitizer.sanitizeValue(diagnosticReason, 1_000));
                return handoffCreateGeneration(
                        taskId, startedAt, "create_template_runtime",
                        CREATE_FAILURE_REASON, diagnosticReason, Map.of());
            }
            log.info("CREATE recipe 路径完成，appId: {}, templateId: {}, filledScopes: {}",
                    app.getId(), result.templateId(), result.filledSlotCount());
            generationPerformanceMonitorService.recordSpan(
                    taskId,
                    "create_template_runtime",
                GenerationSpanCategory.PIPELINE,
                    "success",
                    Duration.between(startedAt, Instant.now()),
                    String.valueOf(telemetry(result))
            );
            generationPerformanceMonitorService.recordCreateTelemetry(taskId, telemetry(result));
            session.emit(GenerationStreamEvent.agentEvent(
                    result.summary(),
                    Map.of(
                            "route", route(),
                            "mode", request.modeDecision().mode().name(),
                            "routerReason", request.modeDecision().reason(),
                            "routingDecisionCode", request.modeDecision().decisionCode().name(),
                            "templateId", result.templateId(),
                            "filledSlots", result.filledSlots(),
                            "totalChars", result.totalChars(),
                            "telemetry", telemetry(result)
                    )
            ));
            CreatePostGenerationValidationService.ValidationOutcome validationOutcome =
                    request.executionPlan() == null
                            ? createPostGenerationValidationService.validate(
                                    app.getId(),
                                    request.taskRequest().loginUser(),
                                    request.codeGenType(),
                                    request.taskRequest().message(),
                                    taskId,
                                    result,
                                    session)
                            : createPostGenerationValidationService.validate(
                                    app.getId(),
                                    request.taskRequest().loginUser(),
                                    request.codeGenType(),
                                    request.taskRequest().message(),
                                    taskId,
                                    result,
                                    session,
                                    request.executionPlan());
            session.throwIfCancelled();
            if (!validationOutcome.success()) {
                return finishValidationFailure(
                        request, taskId, startedAt, validationOutcome.reason(), telemetry(result));
            }
            return finishSuccessfulCreateGeneration(
                    request,
                    taskId,
                    result,
                    buildSuccessResultSummary(result)
            );
        } catch (RuntimeException failure) {
            GenerationTerminalOutcome terminalOutcome = GenerationTerminalOutcome.resolve(session, failure);
            if (terminalOutcome != GenerationTerminalOutcome.FAILED) {
                return finishTerminalCreateGeneration(
                        request, taskId, session, terminalOutcome,
                        terminalReason(terminalOutcome), "CREATE 模板生成", failure.getMessage());
            }
            String diagnosticReason = StrUtil.blankToDefault(
                    failure.getMessage(), failure.getClass().getSimpleName());
            log.error("CREATE 模板路径执行失败，appId: {}, taskId: {}",
                    app.getId(), taskId, LogExceptionSanitizer.sanitize(failure));
            if (result != null || hasNewWorkspaceMutations(session, initialWorkspaceMutations)) {
                return finishTerminalCreateGeneration(
                        request,
                        taskId,
                        session,
                        GenerationTerminalOutcome.FAILED,
                        CREATE_VALIDATION_FAILURE_REASON,
                        "CREATE 写入后处理",
                        diagnosticReason
                );
            }
            return handoffCreateGeneration(
                    taskId, startedAt, "create_template_runtime",
                    CREATE_FAILURE_REASON, diagnosticReason, Map.of());
        }
    }

    /** 返回{@code handoff}创建生成。 */
    private GenerationPipelineOutcome handoffCreateGeneration(String taskId,
                                                               Instant startedAt,
                                                               String spanStage,
                                                               String fallbackReason,
                                                               String diagnosticReason,
                                                               Map<String, Object> createTelemetry) {
        String safeDiagnostic = LogExceptionSanitizer.sanitizeValue(diagnosticReason, 1_000);
        generationPerformanceMonitorService.recordSpan(
                taskId,
                spanStage,
                GenerationSpanCategory.PIPELINE,
                "fallback",
                Duration.between(startedAt, Instant.now()),
                safeDiagnostic
        );
        Map<String, Object> fallbackTelemetry = new LinkedHashMap<>(createTelemetry);
        fallbackTelemetry.put("fallback", true);
        generationPerformanceMonitorService.recordCreateTelemetry(taskId, fallbackTelemetry);
        return GenerationPipelineOutcome.fallback(route(), fallbackReason);
    }

    private GenerationPipelineOutcome finishSuccessfulCreateGeneration(
            GenerationPipelineRequest request,
            String taskId,
            SlotFillResult result,
            String resultSummary
    ) {
        generationPerformanceMonitorService.finishTask(taskId, GenerationTaskStatus.SUCCESS.getValue());
        return GenerationPipelineOutcome.completed(
                route(),
                GenerationTaskStatus.SUCCESS,
                null,
                resultSummary,
                GenerationCompletionEvidenceSet.successfulMutation(
                        request.modeDecision().expectedValidationLevel(),
                        route(),
                        result == null ? 0 : result.patchOperationCount()));
    }

    /** 构建修复预算耗尽后结束 CREATE，避免再次执行整条重型生成链路。 */
    private GenerationPipelineOutcome finishValidationFailure(
            GenerationPipelineRequest request,
            String taskId,
            Instant startedAt,
            String diagnosticReason,
            Map<String, Object> createTelemetry
    ) {
        String safeDiagnostic = LogExceptionSanitizer.sanitizeValue(diagnosticReason, 1_000);
        generationPerformanceMonitorService.recordSpan(
                taskId,
                "create_post_generation_validation",
                GenerationSpanCategory.VALIDATION,
                "failed",
                Duration.between(startedAt, Instant.now()),
                safeDiagnostic
        );
        Map<String, Object> validationTelemetry = new LinkedHashMap<>(createTelemetry);
        validationTelemetry.put("validationFailed", true);
        validationTelemetry.put("fallback", false);
        generationPerformanceMonitorService.recordCreateTelemetry(taskId, validationTelemetry);
        generationPerformanceMonitorService.finishTask(taskId, GenerationTaskStatus.FAILED.getValue());
        generationEventPublisher.publishSafely(
                request.taskRequest(),
                GenerationEventType.TASK_FAILED,
                GenerationTerminalOutcome.FAILED.eventMessage(),
                Map.of(
                        "taskId", taskId,
                        "route", route(),
                        "reason", CREATE_VALIDATION_FAILURE_REASON,
                        "status", GenerationTaskStatus.FAILED.getValue()
                )
        );
        return GenerationPipelineOutcome.completed(
                route(),
                GenerationTaskStatus.FAILED,
                CREATE_VALIDATION_FAILURE_REASON,
                buildFailureResultSummary(
                        "CREATE 构建验证与自动修复",
                        "项目构建或运行时验证未通过，自动修复预算已用尽"
                )
        );
    }

    private GenerationPipelineOutcome finishInitializationFailure(GenerationPipelineRequest request,
                                                                   String taskId,
                                                                   GenerationSession session,
                                                                   boolean lifecycleStarted,
                                                                   RuntimeException failure) {
        GenerationTerminalOutcome outcome = GenerationTerminalOutcome.resolve(session, failure);
        String stage = lifecycleStarted ? "CREATE 初始化" : "CREATE 生命周期初始化";
        return finishTerminalCreateGeneration(
                request, taskId, session, outcome, terminalReason(outcome), stage, failure.getMessage());
    }

    /** 完成{@code Terminal}创建生成并收口相关状态。 */
    private GenerationPipelineOutcome finishTerminalCreateGeneration(GenerationPipelineRequest request,
                                                                      String taskId,
                                                                      GenerationSession session,
                                                                      GenerationTerminalOutcome outcome,
                                                                      String reason,
                                                                      String stage,
                                                                      String diagnosticReason) {
        generationPerformanceMonitorService.finishTask(taskId, outcome.status());
        if (outcome == GenerationTerminalOutcome.CANCELLED) {
            session.emitStopped();
        } else {
            session.emit(GenerationStreamEvent.generationError(
                    terminalMessage(outcome, reason),
                    Map.of("taskId", taskId, "route", route(), "reason", reason)
            ));
        }
        generationEventPublisher.publishSafely(
                request.taskRequest(), outcome.eventType(), outcome.eventMessage(),
                Map.of("taskId", taskId, "route", route(), "reason", reason,
                        "status", outcome.status())
        );
        return GenerationPipelineOutcome.completed(
                route(), outcome.taskStatus(), reason,
                buildFailureResultSummary(stage, diagnosticReason));
    }

    private String terminalReason(GenerationTerminalOutcome outcome) {
        return switch (outcome) {
            case CANCELLED -> CREATE_CANCELLED_REASON;
            case DEADLINE_EXCEEDED -> CREATE_DEADLINE_REASON;
            default -> CREATE_FAILURE_REASON;
        };
    }

    private String terminalMessage(GenerationTerminalOutcome outcome, String reason) {
        if (outcome == GenerationTerminalOutcome.DEADLINE_EXCEEDED) {
            return "CREATE 生成已超过最大执行时间，请稍后重试";
        }
        return CREATE_VALIDATION_FAILURE_REASON.equals(reason)
                ? "CREATE 项目验证失败，请稍后重试"
                : CREATE_FAILURE_MESSAGE;
    }

    private int successfulWorkspaceMutationCount(GenerationSession session) {
        return session == null || session.executionContext() == null
                ? 0
                : session.executionContext().successfulWorkspaceMutationCount();
    }

    private boolean hasNewWorkspaceMutations(GenerationSession session, int initialWorkspaceMutations) {
        return successfulWorkspaceMutationCount(session) > initialWorkspaceMutations;
    }

    private String buildSuccessResultSummary(SlotFillResult result) {
        return "任务状态：成功"
                + "\n执行路径：CREATE"
                + "\n结果摘要：" + StrUtil.blankToDefault(result.summary(), "CREATE 模板项目已生成")
                + "\n模板 ID：" + StrUtil.blankToDefault(result.templateId(), "未知")
                + "\n填充槽位数量：" + result.filledSlotCount()
                + "\n补丁操作数量：" + result.patchOperationCount()
                + "\n生成字符数：" + result.totalChars();
    }

    private String buildFailureResultSummary(String stage, String diagnosticReason) {
        String safeDiagnostic = LogExceptionSanitizer.sanitizeValue(diagnosticReason, 1_000);
        return "任务状态：失败"
                + "\n执行路径：CREATE"
                + "\n失败阶段：" + stage
                + "\n失败原因：" + StrUtil.blankToDefault(safeDiagnostic, CREATE_FAILURE_MESSAGE);
    }

    /** 返回遥测。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> telemetry(SlotFillResult result) {
        if (result == null || result.metadata() == null) {
            return Map.of();
        }
        Object telemetry = result.metadata().get("telemetry");
        if (telemetry instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }
}
