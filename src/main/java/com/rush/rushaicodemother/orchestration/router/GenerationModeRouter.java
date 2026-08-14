package com.rush.rushaicodemother.orchestration.router;

import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationShadowRoutingMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentProfileService;
import com.rush.rushaicodemother.orchestration.learning.GenerationShadowRoutingProperties;
import com.rush.rushaicodemother.orchestration.learning.GenerationShadowRoutingService;
import com.rush.rushaicodemother.orchestration.learning.IntentProfileRoutingDecisionEngine;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 将生产信号委托给有序策略的公共路由外观。 */
@Component
public class GenerationModeRouter {

    private final GenerationRoutingDecisionEngine decisionEngine;
    private final GenerationRoutingTelemetryProvider telemetryProvider;
    private final IntentProfileService intentProfileService;
    private final GenerationShadowRoutingService shadowRoutingService;

    @Autowired
    public GenerationModeRouter(GenerationRoutingDecisionEngine decisionEngine,
                                GenerationRoutingTelemetryProvider telemetryProvider,
                                IntentProfileService intentProfileService,
                                GenerationShadowRoutingService shadowRoutingService) {
        this.decisionEngine = decisionEngine;
        this.telemetryProvider = telemetryProvider;
        this.intentProfileService = intentProfileService;
        this.shadowRoutingService = shadowRoutingService;
    }

    public GenerationModeRouter(GenerationRoutingDecisionEngine decisionEngine) {
        this(
                decisionEngine,
                (appId, userId) -> GenerationRoutingTelemetrySnapshot.unavailable(),
                new IntentProfileService(),
                disabledShadowRoutingService()
        );
    }

    /**
     * 解析一次意图画像并返回主路由选择结果。
     *
     * <p>Shadow Challenger 仅消费同一份画像进行观测，其结果不会替换返回的主路由决策。</p>
     */
    public GenerationRouteSelection select(GenerationTaskRequest request,
                                           CodeGenTypeEnum codeGenType,
                                           GenerationWorkspace workspace) {
        validate(request, codeGenType, workspace);
        Long appId = request.app().getId();
        Long userId = request.loginUser() == null ? request.app().getUserId() : request.loginUser().getId();
        GenerationRoutingTelemetrySnapshot telemetry = telemetryProvider.snapshot(appId, userId);
        IntentProfile intentProfile = intentProfileService.analyze(request, codeGenType, workspace);
        GenerationRoutingSignal signal = GenerationRoutingSignal.from(
                request,
                codeGenType,
                workspace,
                telemetry,
                intentProfile
        );
        GenerationModeDecision champion = decisionEngine.decide(signal);
        shadowRoutingService.evaluate(intentProfile, champion);
        return new GenerationRouteSelection(
                intentProfile, champion, intentProfileService.lexicalRuleVersion());
    }

    /** 为生成请求选择实际生效的主路由。 */
    public GenerationModeDecision route(GenerationTaskRequest request,
                                        CodeGenTypeEnum codeGenType,
                                        GenerationWorkspace workspace) {
        return select(request, codeGenType, workspace).decision();
    }

    private void validate(GenerationTaskRequest request,
                          CodeGenTypeEnum codeGenType,
                          GenerationWorkspace workspace) {
        ThrowUtils.throwIf(request == null || request.app() == null,
                ErrorCode.PARAMS_ERROR, "生成任务请求无效");
        ThrowUtils.throwIf(codeGenType == null,
                ErrorCode.PARAMS_ERROR, "应用代码生成类型无效");
        ThrowUtils.throwIf(workspace == null,
                ErrorCode.PARAMS_ERROR, "生成工作区无效");
    }

    private static GenerationShadowRoutingService disabledShadowRoutingService() {
        return new GenerationShadowRoutingService(
                new GenerationShadowRoutingProperties(),
                new IntentProfileRoutingDecisionEngine(),
                GenerationShadowRoutingMetricsCollector.noOp()
        );
    }
}
