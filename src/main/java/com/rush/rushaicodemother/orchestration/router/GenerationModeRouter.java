package com.rush.rushaicodemother.orchestration.router;

import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 将生产信号委托给有序策略的公共路由外观。 */
@Component
public class GenerationModeRouter {

    private final GenerationRoutingDecisionEngine decisionEngine;
    private final GenerationRoutingTelemetryProvider telemetryProvider;

    @Autowired
    public GenerationModeRouter(GenerationRoutingDecisionEngine decisionEngine,
                                GenerationRoutingTelemetryProvider telemetryProvider) {
        this.decisionEngine = decisionEngine;
        this.telemetryProvider = telemetryProvider;
    }

    public GenerationModeRouter(GenerationRoutingDecisionEngine decisionEngine) {
        this(decisionEngine, (appId, userId) -> GenerationRoutingTelemetrySnapshot.unavailable());
    }

    public GenerationModeDecision route(GenerationTaskRequest request,
                                        CodeGenTypeEnum codeGenType,
                                        GenerationWorkspace workspace) {
        ThrowUtils.throwIf(request == null || request.app() == null,
                ErrorCode.PARAMS_ERROR, "generation task request is invalid");
        ThrowUtils.throwIf(codeGenType == null,
                ErrorCode.PARAMS_ERROR, "application code generation type is invalid");
        ThrowUtils.throwIf(workspace == null,
                ErrorCode.PARAMS_ERROR, "generation workspace is invalid");
        Long appId = request.app().getId();
        Long userId = request.loginUser() == null ? request.app().getUserId() : request.loginUser().getId();
        GenerationRoutingTelemetrySnapshot telemetry = telemetryProvider.snapshot(appId, userId);
        return decisionEngine.decide(GenerationRoutingSignal.from(request, codeGenType, workspace, telemetry));
    }
}
