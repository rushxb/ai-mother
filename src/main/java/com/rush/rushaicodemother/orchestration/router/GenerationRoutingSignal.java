package com.rush.rushaicodemother.orchestration.router;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

import java.util.List;
import java.util.Locale;

/** 路由策略消耗的不可变信号包。 */
public record GenerationRoutingSignal(
        GenerationTaskRequest request,
        CodeGenTypeEnum codeGenType,
        GenerationWorkspace workspace,
        String normalizedMessage,
        GenerationRoutingTelemetrySnapshot telemetry,
        IntentProfile intentProfile
) {

    public GenerationRoutingSignal {
        normalizedMessage = StrUtil.blankToDefault(normalizedMessage, "");
        telemetry = telemetry == null ? GenerationRoutingTelemetrySnapshot.unavailable() : telemetry;
        intentProfile = intentProfile == null ? IntentProfile.unknown() : intentProfile;
    }

    public static GenerationRoutingSignal from(GenerationTaskRequest request,
                                               CodeGenTypeEnum codeGenType,
                                               GenerationWorkspace workspace) {
        return from(request, codeGenType, workspace, GenerationRoutingTelemetrySnapshot.unavailable());
    }

    public static GenerationRoutingSignal from(GenerationTaskRequest request,
                                               CodeGenTypeEnum codeGenType,
                                               GenerationWorkspace workspace,
                                               GenerationRoutingTelemetrySnapshot telemetry) {
        return from(request, codeGenType, workspace, telemetry, IntentProfile.unknown());
    }

    public static GenerationRoutingSignal from(GenerationTaskRequest request,
                                               CodeGenTypeEnum codeGenType,
                                               GenerationWorkspace workspace,
                                               GenerationRoutingTelemetrySnapshot telemetry,
                                               IntentProfile intentProfile) {
        return new GenerationRoutingSignal(
                request,
                codeGenType,
                workspace,
                StrUtil.blankToDefault(request == null ? null : request.message(), "").toLowerCase(Locale.ROOT),
                telemetry,
                intentProfile
        );
    }

    public boolean firstGeneration() {
        return workspace != null && !workspace.exists();
    }

    public boolean existingWorkspace() {
        return workspace != null && workspace.exists();
    }

    public boolean containsAny(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        return keywords.stream().anyMatch(normalizedMessage::contains);
    }

    public boolean looksLikeSmallSingleFileEdit() {
        if (StrUtil.isBlank(normalizedMessage) || normalizedMessage.length() > 160) {
            return false;
        }
        return normalizedMessage.contains("修改")
                || normalizedMessage.contains("调整")
                || normalizedMessage.contains("更改")
                || normalizedMessage.contains("替换")
                || normalizedMessage.contains("改成")
                || normalizedMessage.contains("换成");
    }
}
