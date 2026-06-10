package com.rush.rushaicodemother.orchestration.pipeline;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.create.CreatePostGenerationValidationService;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
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

    private static final long COMPLETED_SESSION_REPLAY_SECONDS = 30;

    private final GenerationAppStateService generationAppStateService;
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
        sessionRegistry.put(app.getId(), session);
        generationAppStateService.markGenerationStarted(app.getId(), AppConstant.GENERATING_STAGE_CREATE);
        generationAppStateService.markGenerationStage(
                app.getId(),
                AppConstant.GENERATING_STAGE_CREATE,
                "CREATE 模板生成已开始，正在填充业务 slot...",
                session
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
        generationEventPublisher.publish(request.taskRequest(), GenerationEventType.TASK_ROUTE, "使用 CREATE 模板生成路径", Map.of(
                "taskId", taskId,
                "route", route(),
                "mode", request.modeDecision().mode().name(),
                "routerReason", request.modeDecision().reason()
        ));
        session.emit(GenerationStreamEvent.generationStage(
                "CREATE 模板生成已开始，正在填充业务 slot...",
                Map.of("stage", AppConstant.GENERATING_STAGE_CREATE, "taskId", taskId, "route", route())
        ));
        Thread.startVirtualThread(() -> runCreateGeneration(request, taskId, startedAt, session));
        return Optional.of(new GenerationTaskResult(taskId, route(), request.workspace(), session.asFlux()));
    }

    private void runCreateGeneration(GenerationPipelineRequest request,
                                     String taskId,
                                     Instant startedAt,
                                     GenerationSession session) {
        App app = request.taskRequest().app();
        try {
            SlotFillResult result = slotFillGenerationService.tryGenerate(app, request.taskRequest(), session);
            if (result == null) {
                String reason = StrUtil.blankToDefault(
                        slotFillGenerationService.consumeLastFailureReason(),
                        "CREATE 模板生成未产生可写入的 slot patch，请检查模板 manifest、slot prompt 或模型返回格式"
                );
                failCreateGeneration(
                        request,
                        taskId,
                        startedAt,
                        session,
                        reason
                );
                return;
            }
            if (session.isCancelled()) {
                finishCreateGeneration(request, taskId, session, "cancelled");
                return;
            }
            log.info("模板 slot 填充路径完成，appId: {}, templateId: {}, filledSlots: {}",
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
                generationEventPublisher.publish(request.taskRequest(), GenerationEventType.TASK_FAILED, "CREATE 生成后验证失败", Map.of(
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
            generationEventPublisher.publish(request.taskRequest(), GenerationEventType.TASK_DONE, "CREATE 模板生成完成", Map.of(
                    "taskId", taskId,
                    "route", route(),
                    "validationExecuted", validationOutcome.executed()
            ));
            generationTaskLifecycleService.charge(taskId);
            finishCreateGeneration(request, taskId, session, "success");
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            failCreateGeneration(request, taskId, startedAt, session, reason);
        }
    }

    private void failCreateGeneration(GenerationPipelineRequest request,
                                      String taskId,
                                      Instant startedAt,
                                      GenerationSession session,
                                      String reason) {
        App app = request.taskRequest().app();
        log.warn("CREATE 模板路径失败，首次生成不会在生成前/生成中升级 Heavy，appId: {}, error: {}", app.getId(), reason);
        generationPerformanceMonitorService.recordSpan(
                taskId,
                "create_template_runtime",
                "failed",
                Duration.between(startedAt, Instant.now()),
                reason
        );
        generationEventPublisher.publish(request.taskRequest(), GenerationEventType.TASK_FAILED, "CREATE 模板生成失败", Map.of(
                "taskId", taskId,
                "route", route(),
                "reason", reason
        ));
        session.emit(GenerationStreamEvent.generationError(
                "CREATE 模板生成失败：" + reason,
                Map.of("taskId", taskId, "route", route(), "reason", reason)
        ));
        finishCreateGeneration(request, taskId, session, "failed");
    }

    private void finishCreateGeneration(GenerationPipelineRequest request,
                                        String taskId,
                                        GenerationSession session,
                                        String status) {
        App app = request.taskRequest().app();
        generationAppStateService.markGenerationFinished(app.getId());
        session.complete();
        sessionRegistry.cleanupLater(app.getId(), session, COMPLETED_SESSION_REPLAY_SECONDS);
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
