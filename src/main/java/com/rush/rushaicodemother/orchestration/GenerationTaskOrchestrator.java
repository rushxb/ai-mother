package com.rush.rushaicodemother.orchestration;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationModeRouter;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskControlService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSubmissionService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Public generation use-case facade.
 *
 * <p>The request thread only validates, resolves routing metadata and submits a task. Pipeline
 * execution and fallback are owned by the asynchronous task runtime.</p>
 */
@Component
@RequiredArgsConstructor
public class GenerationTaskOrchestrator {

    private final GenerationSessionRegistry generationSessionRegistry;
    private final GenerationModeRouter generationModeRouter;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationTaskSubmissionService generationTaskSubmissionService;
    private final GenerationTaskControlService generationTaskControlService;

    public GenerationTaskResult start(GenerationTaskRequest request) {
        ThrowUtils.throwIf(request == null || request.app() == null || request.loginUser() == null,
                ErrorCode.PARAMS_ERROR, "生成任务参数错误");
        ThrowUtils.throwIf(StrUtil.isBlank(request.message()), ErrorCode.PARAMS_ERROR, "提示词不能为空");

        App app = request.app();
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(codeGenType == null, ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");
        GenerationWorkspace workspace = generationWorkspaceService.resolve(app, codeGenType);
        GenerationModeDecision decision = generationModeRouter.route(request, codeGenType, workspace);

        return generationTaskSubmissionService.submit(new GenerationPipelineRequest(
                request,
                codeGenType,
                workspace,
                decision
        ));
    }

    public Flux<GenerationStreamEvent> getStream(Long appId) {
        GenerationSession session = generationSessionRegistry.get(appId);
        ThrowUtils.throwIf(session == null, ErrorCode.OPERATION_ERROR, "当前应用没有可订阅的生成任务");
        return session.asFlux();
    }

    public void stop(Long appId, User loginUser) {
        generationTaskControlService.cancelActiveForApp(appId, loginUser);
    }
}
