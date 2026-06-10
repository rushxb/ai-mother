package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.edit.AgentEditGenerationService;
import com.rush.rushaicodemother.orchestration.edit.AgentEditResult;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Order(30)
@Component
@RequiredArgsConstructor
public class AgentEditGenerationPipeline implements GenerationPipeline {

    private static final long COMPLETED_SESSION_REPLAY_SECONDS = 30;

    private final AgentEditGenerationService agentEditGenerationService;
    private final GenerationPerformanceMonitorService generationPerformanceMonitorService;
    private final GenerationSessionRegistry sessionRegistry;

    @Override
    public String route() {
        return GenerationRoute.AGENT_EDIT;
    }

    @Override
    public boolean supports(GenerationPipelineRequest request) {
        return request.modeIs(GenerationMode.AGENT_EDIT);
    }

    @Override
    public Optional<GenerationTaskResult> execute(GenerationPipelineRequest request) {
        App app = request.taskRequest().app();
        Instant startedAt = Instant.now();
        try {
            AgentEditResult editResult = agentEditGenerationService.execute(request.taskRequest(), request.modeDecision());
            if (editResult == null) {
                return Optional.empty();
            }
            generationPerformanceMonitorService.recordSpan(
                    editResult.taskId(),
                    "agent_edit_pipeline",
                    editResult.status(),
                    Duration.between(startedAt, Instant.now()),
                    "repairRounds=" + editResult.repairRounds()
            );
            if ("failed".equals(editResult.status())) {
                return Optional.empty();
            }
            GenerationSession session = new GenerationSession(null);
            sessionRegistry.put(app.getId(), session);
            session.emit(GenerationStreamEvent.agentEvent(
                    editResult.summary(),
                    Map.of(
                            "route", editResult.route(),
                            "mode", request.modeDecision().mode().name(),
                            "routerReason", request.modeDecision().reason(),
                            "taskId", editResult.taskId(),
                            "status", editResult.status(),
                            "repairRounds", editResult.repairRounds()
                    )
            ));
            session.complete();
            sessionRegistry.cleanupLater(app.getId(), session, COMPLETED_SESSION_REPLAY_SECONDS);
            generationPerformanceMonitorService.finishTask(editResult.taskId(), "success");
            return Optional.of(new GenerationTaskResult(editResult.taskId(), editResult.route(), request.workspace(), session.asFlux()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
