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
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskIdempotency;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSubmissionService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 公共生成用例外观。
 *
 * <p>请求线程仅验证、解析路由元数据并提交任务。管道
 * 执行和回退由异步任务运行时拥有。</p>
 */
@Component
@RequiredArgsConstructor
public class GenerationTaskOrchestrator {

    private final GenerationSessionRegistry generationSessionRegistry;
    private final GenerationModeRouter generationModeRouter;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationTaskSubmissionService generationTaskSubmissionService;
    private final GenerationTaskControlService generationTaskControlService;

    /**
 * 启动生成任务{@code Orchestrator}。
 *
 * @param request 请求参数
 * @return 生成任务{@code Orchestrator}
 */
    public GenerationTaskResult start(GenerationTaskRequest request) {
        return start(request, GenerationTaskIdempotency.none());
    }

    /**
 * 启动生成任务{@code Orchestrator}。
 *
 * @param request 请求参数
 * @param idempotency {@code idempotency} 对应的调用参数
 * @return 生成任务{@code Orchestrator}
 */
    public GenerationTaskResult start(GenerationTaskRequest request,
                                      GenerationTaskIdempotency idempotency) {
        ThrowUtils.throwIf(request == null || request.app() == null || request.loginUser() == null,
                ErrorCode.PARAMS_ERROR, "生成任务参数错误");
        ThrowUtils.throwIf(StrUtil.isBlank(request.message()), ErrorCode.PARAMS_ERROR, "提示词不能为空");
        ThrowUtils.throwIf(idempotency == null, ErrorCode.PARAMS_ERROR, "生成任务幂等参数错误");

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
        ), idempotency);
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
