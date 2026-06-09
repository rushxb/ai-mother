package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.orchestration.edit.LightweightEditResult;
import com.rush.rushaicodemother.orchestration.edit.LightweightEditService;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Order(10)
@Component
@RequiredArgsConstructor
public class LightweightEditGenerationPipeline implements GenerationPipeline {

    private static final long COMPLETED_SESSION_REPLAY_SECONDS = 30;

    private final GenerationAppStateService generationAppStateService;
    private final GenerationSessionRegistry sessionRegistry;
    private final LightweightEditService lightweightEditService;

    @Override
    public String route() {
        return GenerationRoute.LIGHTWEIGHT_EDIT;
    }

    @Override
    public boolean supports(GenerationPipelineRequest request) {
        return true;
    }

    @Override
    public Optional<GenerationTaskResult> execute(GenerationPipelineRequest request) {
        App app = request.taskRequest().app();
        try {
            LightweightEditResult editResult = lightweightEditService.execute(request.taskRequest());
            if (editResult == null) {
                return Optional.empty();
            }
            log.info("轻量编辑路径完成，appId: {}, taskId: {}, route: {}", app.getId(), editResult.taskId(), editResult.route());
            if ("failed".equals(editResult.validationResult())) {
                generationAppStateService.markGenerationFinished(app.getId());
                throw new BusinessException(ErrorCode.OPERATION_ERROR, editResult.summary());
            }
            GenerationSession session = new GenerationSession(null);
            sessionRegistry.put(app.getId(), session);
            session.emit(GenerationStreamEvent.agentEvent(
                    editResult.summary(),
                    Map.of("route", editResult.route(), "taskId", editResult.taskId(), "status", editResult.validationResult())
            ));
            session.complete();
            sessionRegistry.cleanupLater(app.getId(), session, COMPLETED_SESSION_REPLAY_SECONDS);
            return Optional.of(new GenerationTaskResult(editResult.taskId(), editResult.route(), request.workspace(), session.asFlux()));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("轻量编辑路径异常，回退到后续生成管线，appId: {}, error: {}", app.getId(), e.getMessage());
            return Optional.empty();
        }
    }
}
