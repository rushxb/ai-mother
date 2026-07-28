package com.rush.rushaicodemother.core.builder;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandResult;

/** Go 项目构建测试的结构化结果。 */
public record GoBuildResult(
        boolean success,
        String stage,
        String projectPath,
        String summary,
        ProjectCommandResult testResult
) {

    public GoBuildResult {
        stage = StrUtil.blankToDefault(stage, "prepare");
        projectPath = StrUtil.nullToEmpty(projectPath);
        summary = StrUtil.nullToEmpty(summary);
    }

    public static GoBuildResult invalid(String projectPath, String summary) {
        return new GoBuildResult(false, "prepare", projectPath, summary, null);
    }

    static GoBuildResult reused(String projectPath) {
        return new GoBuildResult(
                true,
                "reused",
                projectPath,
                "Go 项目源码未变化，已复用本任务内通过的构建测试",
                null
        );
    }

    static GoBuildResult sourceChangedDuringBuild(String projectPath) {
        return new GoBuildResult(
                false,
                "snapshot",
                projectPath,
                "Go 项目在构建测试期间发生变化，需要重新验证",
                null
        );
    }

    /** 根据输入数据创建当前对象。 */
    static GoBuildResult fromCommand(String projectPath, ProjectCommandResult result) {
        if (result == null) {
            return new GoBuildResult(false, "toolchain", projectPath, "Go 构建测试服务未返回结果", null);
        }
        if (result.success()) {
            return new GoBuildResult(true, "done", projectPath, "Go 项目构建测试通过", result);
        }
        if (result.status() == ProjectCommandResult.Status.START_FAILED) {
            return new GoBuildResult(false, "toolchain", projectPath, "Go 工具链启动失败", result);
        }
        if (result.timedOut()) {
            return new GoBuildResult(false, "test", projectPath, "Go 项目构建测试超时", result);
        }
        if (result.status() == ProjectCommandResult.Status.INTERRUPTED) {
            return new GoBuildResult(false, "test", projectPath, "Go 项目构建测试已中断", result);
        }
        return new GoBuildResult(false, "test", projectPath, "Go 项目编译或测试未通过", result);
    }

    public String publicProjectPath() {
        return GoBuildDiagnosticFormatter.publicProjectPath(this);
    }

    public String publicSummary() {
        return GoBuildDiagnosticFormatter.publicSummary(this);
    }

    public String toPublicDiagnosticReport() {
        return GoBuildDiagnosticFormatter.toPublicDiagnosticReport(this);
    }

    public String toPublicFailureSummary() {
        return GoBuildDiagnosticFormatter.toPublicFailureSummary(this);
    }
}
