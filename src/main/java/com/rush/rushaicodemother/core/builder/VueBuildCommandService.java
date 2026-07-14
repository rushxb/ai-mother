package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandExecutor;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandResult;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.service.dependency.DependencyInstallResult;
import com.rush.rushaicodemother.service.dependency.ProjectDependencyInstaller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 执行 Vue 依赖安装、轻量校验和构建命令，并统一记录性能跨度与结构化结果。
 */
@Slf4j
@Component
public class VueBuildCommandService {

    private final ProjectDependencyInstaller projectDependencyInstaller;
    private final GenerationPerformanceMonitorService performanceMonitorService;
    private final ProjectCommandExecutor projectCommandExecutor;
    private final ProjectCommandProperties properties;
    private final VueBuildStateStore stateStore;

    public VueBuildCommandService(
            ProjectDependencyInstaller projectDependencyInstaller,
            GenerationPerformanceMonitorService performanceMonitorService,
            ProjectCommandExecutor projectCommandExecutor,
            ProjectCommandProperties properties,
            VueBuildStateStore stateStore
    ) {
        this.projectDependencyInstaller = projectDependencyInstaller;
        this.performanceMonitorService = performanceMonitorService;
        this.projectCommandExecutor = projectCommandExecutor;
        this.properties = properties;
        this.stateStore = stateStore;
    }

    VueBuildCommandResult installDependencies(
            Path projectRoot,
            boolean dependenciesReady,
            String dependencyFingerprint,
            String taskId
    ) {
        if (dependenciesReady) {
            log.info("依赖目录和指纹均未变化，跳过 pnpm install: {}", projectRoot);
            return VueBuildCommandResult.skipped("pnpm install", "依赖未变化，已跳过 pnpm install");
        }

        GenerationPerformanceMonitorService.SpanTimer span =
                performanceMonitorService.startSpan(taskId, "pnpm_install");
        try {
            DependencyInstallResult result = projectDependencyInstaller.ensureInstalled(projectRoot, taskId);
            if (!result.success()) {
                span.failed(result.errorDetail());
                return VueBuildCommandResult.failed("pnpm install", 1, result.output());
            }
            span.success();
            persistInstalledDependencyFingerprint(projectRoot, dependencyFingerprint);
            return VueBuildCommandResult.success("pnpm install", 0, result.output());
        } catch (GenerationExecutionPolicyException exception) {
            span.failed(exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            span.failed(exception.getMessage());
            return VueBuildCommandResult.exception("pnpm install", exception.getMessage());
        }
    }

    VueBuildCommandResult executeLightValidation(Path projectRoot, VueProjectScripts scripts, String taskId) {
        String script = scripts.lightValidationScript();
        if (script == null) {
            return VueBuildCommandResult.skipped("light validation", "package.json 中未找到轻量校验脚本");
        }
        log.info("执行轻量校验: pnpm run {}", script);
        return executeCommand(
                "light_validate",
                taskId,
                projectRoot,
                script,
                properties.getLightValidationTimeout()
        );
    }

    VueBuildCommandResult executeLightBuild(Path projectRoot, VueProjectScripts scripts, String taskId) {
        String script = scripts.lightBuildScript();
        if (script == null) {
            return VueBuildCommandResult.exception("pnpm run build", "package.json 中未找到可用的轻量构建脚本");
        }
        log.info("执行轻量构建: pnpm run {}", script);
        return executeCommand("light_build", taskId, projectRoot, script, properties.getLightBuildTimeout());
    }

    VueBuildCommandResult executeFullBuild(Path projectRoot, VueProjectScripts scripts, String taskId) {
        String script = scripts.fullBuildScript();
        if (script == null) {
            return VueBuildCommandResult.exception("pnpm run build", "package.json 中未找到可用的构建脚本");
        }
        log.info("执行全量构建: pnpm run {}", script);
        return executeCommand("full_build", taskId, projectRoot, script, properties.getFullBuildTimeout());
    }

    private VueBuildCommandResult executeCommand(
            String performanceStage,
            String taskId,
            Path projectRoot,
            String script,
            Duration timeout
    ) {
        GenerationPerformanceMonitorService.SpanTimer span =
                performanceMonitorService.startSpan(taskId, performanceStage);
        try {
            ProjectCommandResult result = projectCommandExecutor.executePnpmScript(
                    projectRoot,
                    script,
                    timeout,
                    taskId,
                    performanceStage + ":" + projectRoot
            );
            if (result.success()) {
                span.success();
                return VueBuildCommandResult.success(
                        result.command(),
                        result.exitCode() == null ? 0 : result.exitCode(),
                        result.output()
                );
            }
            span.close(result.timedOut() ? "timeout" : "failed", result.command());
            return VueBuildCommandResult.fromProjectCommand(result);
        } catch (GenerationExecutionPolicyException exception) {
            span.failed(exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            span.failed(exception.getMessage());
            return VueBuildCommandResult.exception("pnpm run " + script, exception.getMessage());
        }
    }

    private void persistInstalledDependencyFingerprint(Path projectRoot, String dependencyFingerprint) {
        try {
            stateStore.recordDependencyInstalled(projectRoot, dependencyFingerprint);
        } catch (Exception exception) {
            // 安装已经成功，状态写入失败只能降低下一次缓存命中率，不应重复执行安装或判定本次失败。
            log.warn("依赖安装成功，但写入 Vue 依赖指纹失败: {}, {}", projectRoot, LogExceptionSanitizer.sanitizeMessage(exception));
        }
    }
}
