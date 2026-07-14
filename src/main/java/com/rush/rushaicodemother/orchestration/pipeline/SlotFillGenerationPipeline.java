package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.create.CreatePostGenerationValidationService;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import com.rush.rushaicodemother.orchestration.template.SlotFillGenerationService;
import com.rush.rushaicodemother.orchestration.template.SlotFillResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Order(20)
@Component
@RequiredArgsConstructor
public class SlotFillGenerationPipeline implements GenerationPipeline {
    private static final String CREATE_FAILURE_MESSAGE = "CREATE 模板生成失败，请稍后重试";
    private static final String CREATE_FAILURE_REASON = "create_generation_failed";

    private final GenerationTaskLifecycleService generationTaskLifecycleService;
    private final GenerationPerformanceMonitorService generationPerformanceMonitorService;
    private final CreatePostGenerationValidationService createPostGenerationValidationService;
    private final GenerationEventPublisher generationEventPublisher;
    private final GenerationSessionRegistry sessionRegistry;
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
                && !request.workspace().exists();
    }

    @Override
    public Optional<GenerationTaskResult> execute(GenerationPipelineRequest request) {
        App app = request.taskRequest().app();
        Instant startedAt = Instant.now();
        String taskId = "slot_fill_" + System.currentTimeMillis();
        GenerationSession session = new GenerationSession(null);
        boolean lifecycleStarted = false;
        boolean performanceStarted = false;
        try {
            generationTaskLifecycleService.recordUserMessage(
                    app, request.taskRequest().loginUser(), request.taskRequest().message());
            generationTaskLifecycleService.startGeneration(
                    taskId,
                    app,
                    request.taskRequest().loginUser(),
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
                    "CREATE 模板生成已开始，正在填充业务 slot..."
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
            performanceStarted = true;
            generationEventPublisher.publishSafely(
                    request.taskRequest(), GenerationEventType.TASK_ROUTE, "使用 CREATE 模板生成路径", Map.of(
                            "taskId", taskId,
                            "route", route(),
                            "mode", request.modeDecision().mode().name(),
                            "routerReason", request.modeDecision().reason()
                    ));
            session.emit(GenerationStreamEvent.generationStage(
                    "CREATE 模板生成已开始，正在填充业务 slot...",
                    Map.of("stage", AppConstant.GENERATING_STAGE_CREATE,
                            "taskId", taskId, "route", route())
            ));
            sessionRegistry.put(app.getId(), session);
            Thread.startVirtualThread(() -> runCreateGeneration(request, taskId, startedAt, session));
            return Optional.of(new GenerationTaskResult(
                    taskId, route(), request.workspace(), session.asFlux()));
        } catch (RuntimeException startFailure) {
            sessionRegistry.remove(app.getId(), session);
            if (lifecycleStarted) {
                try {
                    generationTaskLifecycleService.completeGeneration(
                            taskId, app.getId(), GenerationTaskStatus.FAILED,
                            "create_generation_start_failed");
                } catch (RuntimeException cleanupFailure) {
                    startFailure.addSuppressed(cleanupFailure);
                }
            }
            if (performanceStarted) {
                try {
                    generationPerformanceMonitorService.finishTask(taskId, "failed");
                } catch (RuntimeException cleanupFailure) {
                    startFailure.addSuppressed(cleanupFailure);
                }
            }
            throw startFailure;
        }
    }

    private void runCreateGeneration(GenerationPipelineRequest request,
                                     String taskId,
                                     Instant startedAt,
                                     GenerationSession session) {
        App app = request.taskRequest().app();
        try {
            SlotFillResult result = slotFillGenerationService.tryGenerate(app, request.taskRequest(), session);
            if (result == null) {
                String diagnosticReason = StrUtil.blankToDefault(
                        slotFillGenerationService.consumeLastFailureReason(),
                        "CREATE recipe 运行时未产生可写入 patch，请检查模板 recipe、spec 归一化或本地渲染结果"
                );
                log.warn("CREATE 模板路径未生成有效补丁，appId: {}, reason: {}", app.getId(),
                        LogExceptionSanitizer.sanitizeValue(diagnosticReason, 1_000));
                failCreateGeneration(
                        request,
                        taskId,
                        startedAt,
                        session,
                        diagnosticReason
                );
                return;
            }
            if (session.isCancelled()) {
                finishCreateGeneration(request, taskId, session, "cancelled");
                return;
            }
            log.info("CREATE recipe 路径完成，appId: {}, templateId: {}, filledScopes: {}",
                    app.getId(), result.templateId(), result.filledSlotCount());
            generationPerformanceMonitorService.recordSpan(
                    taskId,
                    "create_template_runtime",
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
            if (!validationOutcome.success()) {
                generationEventPublisher.publishSafely(request.taskRequest(), GenerationEventType.TASK_FAILED, "CREATE 生成后验证失败", Map.of(
                        "taskId", taskId,
                        "route", route(),
                        "reason", validationOutcome.reason()
                ));
                session.emit(GenerationStreamEvent.generationError(
                        "CREATE 生成后验证失败：" + validationOutcome.reason(),
                        Map.of("taskId", taskId, "route", route(), "reason", validationOutcome.reason())
                ));
                finishCreateGeneration(request, taskId, session, "failed");
                return;
            }
            generationEventPublisher.publishSafely(request.taskRequest(), GenerationEventType.TASK_DONE, "CREATE 模板生成完成", Map.of(
                    "taskId", taskId,
                    "route", route(),
                    "validationExecuted", validationOutcome.executed()
            ));
            finishCreateGeneration(request, taskId, session, "success");
        } catch (Exception e) {
            String diagnosticReason = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
            log.error("CREATE 模板路径执行失败，appId: {}, taskId: {}", app.getId(), taskId,
                    LogExceptionSanitizer.sanitize(e));
            failCreateGeneration(request, taskId, startedAt, session, diagnosticReason);
        }
    }

    private void failCreateGeneration(GenerationPipelineRequest request,
                                      String taskId,
                                      Instant startedAt,
                                      GenerationSession session,
                                      String diagnosticReason) {
        generationPerformanceMonitorService.recordSpan(
                taskId,
                "create_template_runtime",
                "failed",
                Duration.between(startedAt, Instant.now()),
                diagnosticReason
        );
        generationEventPublisher.publishSafely(request.taskRequest(), GenerationEventType.TASK_FAILED, "CREATE 模板生成失败", Map.of(
                "taskId", taskId,
                "route", route(),
                "reason", CREATE_FAILURE_REASON
        ));
        session.emit(GenerationStreamEvent.generationError(
                CREATE_FAILURE_MESSAGE,
                Map.of("taskId", taskId, "route", route(), "reason", CREATE_FAILURE_REASON)
        ));
        finishCreateGeneration(request, taskId, session, "failed");
    }

    private void finishCreateGeneration(GenerationPipelineRequest request,
                                        String taskId,
                                        GenerationSession session,
                                        String status) {
        App app = request.taskRequest().app();
        GenerationTaskStatus taskStatus = GenerationTaskStatus.fromValue(status);
        if (taskStatus == null || !taskStatus.isTerminal()) {
            throw new IllegalArgumentException("unsupported CREATE terminal status: " + status);
        }
        if (taskStatus == GenerationTaskStatus.SUCCESS) {
            generationTaskLifecycleService.completeGenerationAndCharge(
                    taskId, app.getId(), taskStatus, null);
        } else {
            generationTaskLifecycleService.completeGeneration(
                    taskId, app.getId(), taskStatus, CREATE_FAILURE_REASON);
        }
        session.complete();
        sessionRegistry.retainForReplay(app.getId(), session);
        generationPerformanceMonitorService.finishTask(taskId, status);
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
