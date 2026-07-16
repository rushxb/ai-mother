package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.edit.LightweightEditResult;
import com.rush.rushaicodemother.orchestration.edit.LightweightEditService;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Order(10)
@Component
@RequiredArgsConstructor
public class LightweightEditGenerationPipeline implements GenerationPipeline {

    private final GenerationPerformanceMonitorService generationPerformanceMonitorService;
    private final LightweightEditService lightweightEditService;

    @Override
    public String route() {
        return GenerationRoute.LIGHTWEIGHT_EDIT;
    }

    @Override
    public boolean supports(GenerationPipelineRequest request) {
        return request.modeIs(GenerationMode.LIGHT_EDIT);
    }

    @Override
    public GenerationPipelineOutcome execute(GenerationPipelineRequest request) {
        GenerationTaskExecution execution = request.requireExecution();
        GenerationSession session = execution.session();
        App app = request.taskRequest().app();
        Instant startedAt = Instant.now();
        try {
            session.throwIfCancelled();
            LightweightEditResult editResult = lightweightEditService.execute(
                    execution.taskId(), request.taskRequest());
            if (editResult == null) {
                return GenerationPipelineOutcome.fallback(route(), "lightweight_edit_not_applicable");
            }
            assertTaskIdentity(execution.taskId(), editResult.taskId());
            String status = "failed".equals(editResult.validationResult()) ? "failed" : "success";
            generationPerformanceMonitorService.startTask(
                    execution.taskId(),
                    app.getId(),
                    request.taskRequest().loginUser().getId(),
                    editResult.route(),
                    request.codeGenType().getValue(),
                    startedAt,
                    request.modeDecision()
            );
            generationPerformanceMonitorService.recordSpan(
                    execution.taskId(),
                    "lightweight_edit",
                    GenerationSpanCategory.PIPELINE,
                    status,
                    Duration.between(startedAt, Instant.now()),
                    editResult.route()
            );
            if ("failed".equals(status)) {
                session.emit(GenerationStreamEvent.generationError(
                        editResult.summary(),
                        Map.of(
                                "route", editResult.route(),
                                "taskId", execution.taskId(),
                                "status", status
                        )
                ));
            } else {
                session.emit(GenerationStreamEvent.agentEvent(
                        editResult.summary(),
                        Map.of(
                                "route", editResult.route(),
                                "mode", request.modeDecision().mode().name(),
                                "routerReason", request.modeDecision().reason(),
                                "taskId", execution.taskId(),
                                "status", editResult.validationResult()
                        )
                ));
            }
            generationPerformanceMonitorService.finishTask(execution.taskId(), status);
            log.info("轻量编辑路径完成，appId: {}, taskId: {}, route: {}, status: {}",
                    app.getId(), execution.taskId(), editResult.route(), status);
            return GenerationPipelineOutcome.completed(
                    route(), "success".equals(status) ? GenerationTaskStatus.SUCCESS : GenerationTaskStatus.FAILED);
        } catch (RuntimeException failure) {
            log.warn("轻量编辑路径执行失败，appId: {}, taskId: {}, error: {}",
                    app.getId(), execution.taskId(), LogExceptionSanitizer.sanitizeMessage(failure));
            session.emit(GenerationStreamEvent.generationError(
                    "轻量编辑执行失败，请稍后重试",
                    Map.of("route", route(), "taskId", execution.taskId(), "status", "failed")
            ));
            generationPerformanceMonitorService.finishTask(execution.taskId(), "failed");
            return GenerationPipelineOutcome.completed(route(), GenerationTaskStatus.FAILED);
        }
    }

    private void assertTaskIdentity(String expectedTaskId, String actualTaskId) {
        if (!expectedTaskId.equals(actualTaskId)) {
            throw new IllegalStateException("lightweight edit returned a different taskId");
        }
    }
}
