package com.rush.rushaicodemother.orchestration.router;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

import java.util.List;
import java.util.Locale;

/** 路由策略消耗的不可变信号包。 */
public record GenerationRoutingSignal(
        GenerationTaskRequest request,
        CodeGenTypeEnum codeGenType,
        GenerationWorkspace workspace,
        String normalizedMessage,
        GenerationRoutingTelemetrySnapshot telemetry
) {

    /**
 * 根据输入数据创建当前对象。
 *
 * @param request 请求参数
 * @param codeGenType 代码生成类型
 * @param workspace 工作区
 * @return 生成路由{@code Signal}
 */
    public static GenerationRoutingSignal from(GenerationTaskRequest request,
                                               CodeGenTypeEnum codeGenType,
                                               GenerationWorkspace workspace) {
        return from(request, codeGenType, workspace, GenerationRoutingTelemetrySnapshot.unavailable());
    }

    /**
 * 根据输入数据创建当前对象。
 *
 * @param request 请求参数
 * @param codeGenType 代码生成类型
 * @param workspace 工作区
 * @param telemetry 遥测
 * @return 生成路由{@code Signal}
 */
    public static GenerationRoutingSignal from(GenerationTaskRequest request,
                                               CodeGenTypeEnum codeGenType,
                                               GenerationWorkspace workspace,
                                               GenerationRoutingTelemetrySnapshot telemetry) {
        return new GenerationRoutingSignal(
                request,
                codeGenType,
                workspace,
                StrUtil.blankToDefault(request == null ? null : request.message(), "").toLowerCase(Locale.ROOT),
                telemetry == null ? GenerationRoutingTelemetrySnapshot.unavailable() : telemetry
        );
    }

    public boolean firstGeneration() {
        return workspace != null && !workspace.exists();
    }

    public boolean existingWorkspace() {
        return workspace != null && workspace.exists();
    }

    /**
 * 返回{@code contains}{@code Any}。
 *
 * @param keywords 待处理的 {@code keywords} 集合
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean containsAny(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        return keywords.stream().anyMatch(normalizedMessage::contains);
    }

    /**
 * 返回{@code looks}{@code Like}{@code Small}{@code Single}文件编辑。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
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
