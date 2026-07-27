package com.rush.rushaicodemother.core.builder;

import cn.hutool.core.util.StrUtil;

/**
 * Vue 项目构建的不可变结果，也是内部诊断与公开诊断的统一访问入口。
 */
public record VueBuildResult(
        boolean success,
        String stage,
        String projectPath,
        String summary,
        VueBuildCommandResult installResult,
        VueBuildCommandResult buildResult
) {

    public VueBuildResult {
        stage = StrUtil.blankToDefault(stage, "prepare");
        projectPath = StrUtil.nullToEmpty(projectPath);
        summary = StrUtil.nullToEmpty(summary);
    }

    static VueBuildResult invalid(String projectPath, String summary) {
        return new VueBuildResult(false, "prepare", projectPath, summary, null, null);
    }

    static VueBuildResult installFailed(String projectPath, VueBuildCommandResult installResult) {
        return new VueBuildResult(false, "install", projectPath,
                VueBuildDiagnosticFormatter.commandSummary(installResult, "安装失败"), installResult, null);
    }

    static VueBuildResult lightBuildFailed(String projectPath, VueBuildCommandResult installResult,
                                           VueBuildCommandResult buildResult) {
        return new VueBuildResult(false, "build-light", projectPath,
                VueBuildDiagnosticFormatter.commandSummary(buildResult, "轻量构建失败"), installResult, buildResult);
    }

    static VueBuildResult lightValidateFailed(String projectPath, VueBuildCommandResult installResult,
                                              VueBuildCommandResult validateResult) {
        return new VueBuildResult(false, "validate-light", projectPath,
                VueBuildDiagnosticFormatter.commandSummary(validateResult, "轻量校验失败"), installResult,
                validateResult);
    }

    static VueBuildResult buildFailed(String projectPath, VueBuildCommandResult installResult,
                                      VueBuildCommandResult buildResult) {
        return new VueBuildResult(false, "build", projectPath,
                VueBuildDiagnosticFormatter.commandSummary(buildResult, "全量构建失败"), installResult, buildResult);
    }

    static VueBuildResult distMissing(String projectPath, VueBuildCommandResult installResult,
                                      VueBuildCommandResult buildResult) {
        return new VueBuildResult(false, "dist", projectPath, "构建完成但未生成 dist 目录",
                installResult, buildResult);
    }

    static VueBuildResult success(String projectPath, VueBuildCommandResult installResult,
                                  VueBuildCommandResult buildResult) {
        return new VueBuildResult(true, "done", projectPath, "Vue 项目构建成功", installResult, buildResult);
    }

    static VueBuildResult lightSuccess(String projectPath, VueBuildCommandResult installResult,
                                       VueBuildCommandResult buildResult) {
        return new VueBuildResult(true, "light-done", projectPath, "轻量构建通过并刷新 dist",
                installResult, buildResult);
    }

    static VueBuildResult dependencyRefreshSuccess(String projectPath, VueBuildCommandResult installResult,
                                                   VueBuildCommandResult buildResult) {
        return new VueBuildResult(true, "dependency-refresh", projectPath, "依赖刷新后轻量构建通过",
                installResult, buildResult);
    }

    static VueBuildResult reused(String projectPath) {
        VueBuildCommandResult installResult = VueBuildCommandResult.skipped(
                "pnpm install --prefer-offline",
                "依赖和源码未变化，已跳过 pnpm install"
        );
        VueBuildCommandResult buildResult = VueBuildCommandResult.skipped(
                "reuse dist",
                "依赖和源码未变化，复用现有 dist"
        );
        return new VueBuildResult(true, "reuse", projectPath, "依赖和源码未变化，复用现有 dist",
                installResult, buildResult);
    }

    static VueBuildResult taskReused(String projectPath) {
        VueBuildCommandResult installResult = VueBuildCommandResult.skipped(
                "pnpm install --prefer-offline",
                "已复用本任务内通过的依赖校验"
        );
        VueBuildCommandResult buildResult = VueBuildCommandResult.skipped(
                "复用任务构建结果",
                "当前源码和 dist 与本任务内通过的构建快照一致"
        );
        return new VueBuildResult(
                true,
                "task-reuse",
                projectPath,
                "当前源码和 dist 未变化，已复用本任务内通过的构建结果",
                installResult,
                buildResult
        );
    }

    static VueBuildResult sourceChangedDuringBuild(String projectPath) {
        return new VueBuildResult(
                false,
                "snapshot",
                projectPath,
                "Vue 项目在构建验证期间发生变化或无法确认快照，需要重新验证",
                null,
                null
        );
    }

    public String publicProjectPath() {
        return VueBuildDiagnosticFormatter.publicProjectPath(this);
    }

    public String publicSummary() {
        return VueBuildDiagnosticFormatter.publicSummary(this);
    }

    public String toInternalDiagnosticReport() {
        return VueBuildDiagnosticFormatter.toInternalDiagnosticReport(this);
    }

    public String toPublicDiagnosticReport() {
        return VueBuildDiagnosticFormatter.toPublicDiagnosticReport(this);
    }

    public String toInternalFailureSummary() {
        return VueBuildDiagnosticFormatter.toInternalFailureSummary(this);
    }

    public String toPublicFailureSummary() {
        return VueBuildDiagnosticFormatter.toPublicFailureSummary(this);
    }

    public String validationTier() {
        return switch (stage) {
            case "reuse", "task-reuse" -> "复用";
            case "light-done", "build-light", "validate-light", "dependency-refresh" -> "轻量";
            case "done", "build" -> "全量";
            case "install" -> "安装";
            case "dist" -> "产物检查";
            case "snapshot" -> "快照校验";
            default -> "准备";
        };
    }

    public String repairPriority() {
        return switch (stage) {
            case "install", "validate-light", "build-light", "build" -> "高";
            case "dist", "prepare", "snapshot" -> "中";
            default -> "低";
        };
    }

    public String executionPath() {
        return switch (stage) {
            case "reuse" -> "复用现有 dist，跳过安装和构建";
            case "task-reuse" -> "复用本任务内已通过且产物一致的构建结果";
            case "light-done" -> "跳过安装，执行轻量校验和轻量构建";
            case "dependency-refresh" -> "依赖刷新后执行轻量校验和轻量构建";
            case "validate-light" -> "轻量校验失败";
            case "build-light" -> "轻量构建失败";
            case "done" -> "依赖按指纹校验后执行全量构建";
            case "build" -> "全量构建失败";
            case "install" -> "依赖安装失败";
            case "dist" -> "构建完成后校验 dist";
            case "snapshot" -> "构建完成后校验源码快照稳定性";
            case "prepare" -> "准备阶段";
            default -> stage;
        };
    }
}
