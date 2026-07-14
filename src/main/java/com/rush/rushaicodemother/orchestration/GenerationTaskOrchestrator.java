package com.rush.rushaicodemother.orchestration;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.heavy.HeavyGenerationCoordinator;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipeline;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationModeRouter;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Routes a generation request to a concrete pipeline and owns the public task-control API.
 *
 * <p>The heavy workflow is coordinated by {@link HeavyGenerationCoordinator}; keeping that lifecycle out of
 * this router prevents pipelines from calling back into their own registry owner.</p>
 */
@Component
@RequiredArgsConstructor
public class GenerationTaskOrchestrator {

    private final GenerationEventPublisher generationEventPublisher;
    private final List<GenerationPipeline> generationPipelines;
    private final GenerationSessionRegistry generationSessionRegistry;
    private final HeavyGenerationCoordinator heavyGenerationCoordinator;
    private final GenerationModeRouter generationModeRouter;
    private final GenerationWorkspaceService generationWorkspaceService;

    public GenerationTaskResult start(GenerationTaskRequest request) {
        ThrowUtils.throwIf(request == null || request.app() == null || request.loginUser() == null,
                ErrorCode.PARAMS_ERROR, "生成任务参数错误");
        ThrowUtils.throwIf(StrUtil.isBlank(request.message()), ErrorCode.PARAMS_ERROR, "提示词不能为空");
        App app = request.app();
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(codeGenType == null, ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");
        GenerationWorkspace workspace = generationWorkspaceService.resolve(app, codeGenType);
        GenerationModeDecision decision = generationModeRouter.route(request, codeGenType, workspace);
        GenerationPipelineRequest pipelineRequest = new GenerationPipelineRequest(request, codeGenType, workspace, decision);
        synchronized (generationSessionRegistry.lock(app.getId())) {
            resetResidualGenerationState(app.getId());
            generationSessionRegistry.assertNoActiveSession(app.getId());
            return dispatchRoutedPipeline(pipelineRequest);
        }
    }

    private GenerationTaskResult dispatchRoutedPipeline(GenerationPipelineRequest pipelineRequest) {
        for (GenerationPipeline pipeline : generationPipelines) {
            if (!pipeline.supports(pipelineRequest)) {
                continue;
            }
            var result = pipeline.execute(pipelineRequest);
            if (result.isPresent()) {
                return result.get();
            }
            return handleRoutedPipelineFallback(pipelineRequest, pipeline);
        }
        if (pipelineRequest.modeDecision().mode() == GenerationMode.HEAVY_EXPERT) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "没有可用的专家生成管线");
        }
        return handleRoutedPipelineFallback(pipelineRequest, null);
    }

    private GenerationTaskResult handleRoutedPipelineFallback(GenerationPipelineRequest pipelineRequest,
                                                               GenerationPipeline failedPipeline) {
        GenerationModeDecision decision = pipelineRequest.modeDecision();
        if (decision.fallbackPolicy() != FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT) {
            String route = failedPipeline == null ? decision.route() : failedPipeline.route();
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成模式没有可用管线: " + route);
        }
        if (!pipelineRequest.workspace().exists()) {
            String route = failedPipeline == null ? decision.route() : failedPipeline.route();
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成模式没有可用管线: " + route);
        }
        String failedRoute = failedPipeline == null ? decision.route() : failedPipeline.route();
        String reason = "pipeline_failed_or_unavailable:" + failedRoute;
        generationEventPublisher.publish(pipelineRequest.taskRequest(), GenerationEventType.TASK_ROUTE, "生成路径升级为专家模式", Map.of(
                "mode", GenerationMode.HEAVY_EXPERT.name(),
                "route", GenerationRoute.HEAVY_GENERATION,
                "routerReason", decision.reason(),
                "fallbackReason", reason
        ));
        GenerationModeDecision fallbackDecision = decision.withFallback(GenerationMode.HEAVY_EXPERT, reason);
        return heavyGenerationCoordinator.start(new GenerationPipelineRequest(
                pipelineRequest.taskRequest(),
                pipelineRequest.codeGenType(),
                pipelineRequest.workspace(),
                fallbackDecision
        ));
    }

    public Flux<GenerationStreamEvent> getStream(Long appId) {
        return heavyGenerationCoordinator.getStream(appId);
    }

    public void stop(Long appId, User loginUser) {
        heavyGenerationCoordinator.stop(appId);
    }

    private void resetResidualGenerationState(Long appId) {
        GenerationSession session = generationSessionRegistry.get(appId);
        if (session != null && !session.isActive()) {
            generationSessionRegistry.remove(appId, session);
        }
    }
}
