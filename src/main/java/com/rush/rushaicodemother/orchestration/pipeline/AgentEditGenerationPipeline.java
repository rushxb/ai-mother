package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.edit.AgentEditGenerationService;
import com.rush.rushaicodemother.orchestration.edit.AgentEditResult;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Order(30)
@Component
@RequiredArgsConstructor
public class AgentEditGenerationPipeline implements GenerationPipeline {

    private final AgentEditGenerationService agentEditGenerationService;
    private final GenerationPerformanceMonitorService generationPerformanceMonitorService;

    @Override
    public String route() {
        return GenerationRoute.AGENT_EDIT;
    }

    @Override
    public boolean supports(GenerationPipelineRequest request) {
        return request.modeIs(GenerationMode.AGENT_EDIT);
    }

    @Override
    public GenerationPipelineOutcome execute(GenerationPipelineRequest request) {
        GenerationTaskExecution execution = request.requireExecution();
        GenerationSession session = execution.session();
        App app = request.taskRequest().app();
        Instant startedAt = Instant.now();
        try {
            session.throwIfCancelled();
            AgentEditResult editResult = agentEditGenerationService.execute(
                    execution.taskId(), request.taskRequest(), request.modeDecision(), request.workspace());
            if (editResult == null) {
                return GenerationPipelineOutcome.fallback(route(), "agent_edit_not_applicable");
            }
            assertTaskIdentity(execution.taskId(), editResult.taskId());
            generationPerformanceMonitorService.recordSpan(
                    execution.taskId(),
                    "agent_edit_pipeline",
                    GenerationSpanCategory.PIPELINE,
                    editResult.status(),
                    Duration.between(startedAt, Instant.now()),
                    "repairRounds=" + editResult.repairRounds()
            );
            if ("failed".equals(editResult.status())) {
                session.emit(GenerationStreamEvent.generationError(
                        editResult.summary(),
                        Map.of(
                                "route", editResult.route(),
                                "mode", request.modeDecision().mode().name(),
                                "routerReason", request.modeDecision().reason(),
                                "taskId", execution.taskId(),
                                "status", editResult.status(),
                                "repairRounds", editResult.repairRounds()
                        )
                ));
                generationPerformanceMonitorService.finishTask(execution.taskId(), "failed");
                return GenerationPipelineOutcome.completed(route(), GenerationTaskStatus.FAILED);
            }
            session.emit(GenerationStreamEvent.agentEvent(
                    editResult.summary(),
                    Map.of(
                            "route", editResult.route(),
                            "mode", request.modeDecision().mode().name(),
                            "routerReason", request.modeDecision().reason(),
                            "taskId", execution.taskId(),
                            "status", editResult.status(),
                            "repairRounds", editResult.repairRounds()
                    )
            ));
            generationPerformanceMonitorService.finishTask(execution.taskId(), "success");
            return GenerationPipelineOutcome.completed(route(), GenerationTaskStatus.SUCCESS);
        } catch (GenerationExecutionPolicyException executionPolicyFailure) {
            throw executionPolicyFailure;
        } catch (RuntimeException failure) {
            log.warn("AGENT_EDIT 路径执行失败，appId: {}, taskId: {}, error: {}",
                    app.getId(), execution.taskId(), LogExceptionSanitizer.sanitizeMessage(failure));
            session.emit(GenerationStreamEvent.generationError(
                    "智能编辑执行失败，请稍后重试",
                    Map.of("route", route(), "taskId", execution.taskId(), "status", "failed")
            ));
            generationPerformanceMonitorService.finishTask(execution.taskId(), "failed");
            return GenerationPipelineOutcome.completed(route(), GenerationTaskStatus.FAILED);
        }
    }

    private void assertTaskIdentity(String expectedTaskId, String actualTaskId) {
        if (!expectedTaskId.equals(actualTaskId)) {
            throw new IllegalStateException("agent edit returned a different taskId");
        }
    }
}
