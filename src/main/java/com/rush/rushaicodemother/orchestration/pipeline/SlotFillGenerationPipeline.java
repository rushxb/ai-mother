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

    private GenerationPipelineOutcome runCreateGeneration(GenerationPipelineRequest request,
                                                           String taskId,
                                                           Instant startedAt,
                                                           GenerationSession session) {
        App app = request.taskRequest().app();
        try {
            SlotFillResult result = slotFillGenerationService.tryGenerate(app, request.taskRequest(), session);
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
                    createPostGenerationValidationService.validate(
                            app.getId(),
                            request.taskRequest().loginUser(),
                            request.codeGenType(),
                            request.taskRequest().message(),
                            taskId,
                            result,
                            session
                    );
            session.throwIfCancelled();
            if (!validationOutcome.success()) {
                return handoffCreateGeneration(
                        taskId, startedAt, "create_validation_handoff",
                        CREATE_VALIDATION_FAILURE_REASON, validationOutcome.reason(), telemetry(result));
            }
            return finishSuccessfulCreateGeneration(
                    taskId,
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
            return handoffCreateGeneration(
                    taskId, startedAt, "create_template_runtime",
                    CREATE_FAILURE_REASON, diagnosticReason, Map.of());
        }
    }

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

    private GenerationPipelineOutcome finishSuccessfulCreateGeneration(String taskId,
                                                                        String resultSummary) {
        generationPerformanceMonitorService.finishTask(taskId, GenerationTaskStatus.SUCCESS.getValue());
        return GenerationPipelineOutcome.completed(
                route(), GenerationTaskStatus.SUCCESS, null, resultSummary);
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
                    terminalMessage(outcome),
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

    private String terminalMessage(GenerationTerminalOutcome outcome) {
        return outcome == GenerationTerminalOutcome.DEADLINE_EXCEEDED
                ? "CREATE 生成已超过最大执行时间，请稍后重试"
                : CREATE_FAILURE_MESSAGE;
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
