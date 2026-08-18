package com.rush.rushaicodemother.orchestration.router;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

import java.util.Objects;

/**
 * 路由策略可消费的最小不可变信号。
 *
 * <p>该 interface 刻意不暴露原始请求与 Prompt，确保自然语言只在意图模块解析一次；
 * 路由只能组合结构化画像、工程类型、工作区状态和运行遥测。</p>
 */
public record GenerationRoutingSignal(
        CodeGenTypeEnum codeGenType,
        boolean workspaceExists,
        GenerationRoutingTelemetrySnapshot telemetry,
        IntentProfile intentProfile
) {

    public GenerationRoutingSignal {
        Objects.requireNonNull(codeGenType, "路由工程类型不能为空");
        telemetry = telemetry == null ? GenerationRoutingTelemetrySnapshot.unavailable() : telemetry;
        intentProfile = Objects.requireNonNull(intentProfile, "路由意图画像不能为空");
    }

    public static GenerationRoutingSignal from(CodeGenTypeEnum codeGenType,
                                               GenerationWorkspace workspace,
                                               GenerationRoutingTelemetrySnapshot telemetry,
                                               IntentProfile intentProfile) {
        Objects.requireNonNull(workspace, "路由工作区不能为空");
        return new GenerationRoutingSignal(
                codeGenType,
                workspace.exists(),
                telemetry,
                intentProfile
        );
    }

    public boolean firstGeneration() {
        return !workspaceExists;
    }

    public boolean existingWorkspace() {
        return workspaceExists;
    }
}
