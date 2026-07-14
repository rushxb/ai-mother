package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Vue 项目构建编排入口。
 *
 * <p>文件扫描、状态持久化、脚本解析、命令执行和结果缓存由独立模块负责；本类只维护构建决策顺序。</p>
 */
@Slf4j
@Component
public class VueProjectBuilder {

    private final VueProjectSnapshotService snapshotService;
    private final VueBuildStateStore stateStore;
    private final VueProjectScriptResolver scriptResolver;
    private final VueBuildCommandService commandService;
    private final VueBuildResultRegistry resultRegistry;
    private final GenerationExecutionContextService executionContextService;

    public VueProjectBuilder(
            VueProjectSnapshotService snapshotService,
            VueBuildStateStore stateStore,
            VueProjectScriptResolver scriptResolver,
            VueBuildCommandService commandService,
            VueBuildResultRegistry resultRegistry,
            GenerationExecutionContextService executionContextService
    ) {
        this.snapshotService = snapshotService;
        this.stateStore = stateStore;
        this.scriptResolver = scriptResolver;
        this.commandService = commandService;
        this.resultRegistry = resultRegistry;
        this.executionContextService = executionContextService;
    }

    /** 异步执行 Vue 项目构建。 */
    public void buildProjectAsync(String projectPath) {
        Thread.ofVirtual().name("vue-builder-" + System.currentTimeMillis())
                .start(() -> {
                    try {
                        buildProject(projectPath);
                    } catch (Exception exception) {
                        log.error("异步构建 Vue 项目时发生异常: {}", LogExceptionSanitizer.sanitizeMessage(exception), LogExceptionSanitizer.sanitize(exception));
                    }
                });
    }

    /** 构建 Vue 项目并只返回是否成功。 */
    public boolean buildProject(String projectPath) {
        return buildProjectWithResult(projectPath).success();
    }

    /** 构建 Vue 项目并返回结构化结果。 */
    public VueBuildResult buildProjectWithResult(String projectPath) {
        return executeAndRemember(projectPath, null);
    }

    /** 在生成任务上下文中构建 Vue 项目并返回结构化结果。 */
    public VueBuildResult buildProjectWithResult(String projectPath, String taskId) {
        return executeAndRemember(projectPath, taskId);
    }

    /**
     * 获取与当前项目完整快照严格匹配的最近构建结果；任意依赖或源码变化都会导致缓存失效。
     */
    public VueBuildResult getRecentBuildResult(String projectPath) {
        Path projectRoot = resolveProjectRoot(projectPath);
        if (projectRoot == null) {
            return null;
        }
        Path packageJsonFile = projectRoot.resolve("package.json");
        if (!isSafeRegularFile(packageJsonFile)) {
            return null;
        }
        try {
            JSONObject packageJson = readPackageJson(packageJsonFile);
            VueProjectSnapshot snapshot = snapshotService.capture(projectRoot, packageJson);
            return resultRegistry.find(projectRoot, snapshot);
        } catch (Exception exception) {
            log.debug("读取最近 Vue 构建结果失败: {}, {}", projectPath, LogExceptionSanitizer.sanitizeMessage(exception));
            return null;
        }
    }

    private VueBuildResult executeAndRemember(String projectPath, String taskId) {
        BuildExecution execution = executeBuild(projectPath, taskId);
        if (execution.projectRoot() != null && execution.snapshot() != null) {
            resultRegistry.remember(execution.projectRoot(), execution.snapshot(), execution.result());
        }
        return execution.result();
    }

    private BuildExecution executeBuild(String projectPath, String taskId) {
        Path projectRoot = resolveProjectRoot(projectPath);
        if (projectRoot == null) {
            log.error("Vue 项目目录不存在或无效: {}", projectPath);
            return BuildExecution.invalid(projectPath, "项目目录不存在或无效");
        }

        String normalizedProjectPath = projectRoot.toString();
        Path packageJsonFile = projectRoot.resolve("package.json");
        if (!isSafeRegularFile(packageJsonFile)) {
            log.error("Vue 项目目录中没有安全的 package.json 文件: {}", normalizedProjectPath);
            return BuildExecution.invalid(normalizedProjectPath, "项目目录中没有 package.json 文件");
        }

        log.info("开始构建 Vue 项目: {}", normalizedProjectPath);
        JSONObject packageJson;
        try {
            packageJson = readPackageJson(packageJsonFile);
        } catch (Exception exception) {
            log.error("package.json 解析失败: {}", LogExceptionSanitizer.sanitizeMessage(exception), LogExceptionSanitizer.sanitize(exception));
            return BuildExecution.invalid(normalizedProjectPath, "package.json 解析失败");
        }

        VueProjectSnapshot currentSnapshot;
        try {
            currentSnapshot = snapshotService.capture(projectRoot, packageJson);
        } catch (Exception exception) {
            log.error("Vue 项目指纹计算失败: {}", LogExceptionSanitizer.sanitizeMessage(exception), LogExceptionSanitizer.sanitize(exception));
            return BuildExecution.invalid(normalizedProjectPath, "项目指纹计算失败");
        }

        VueBuildState persistedState = stateStore.read(projectRoot);
        VueProjectScripts scripts = scriptResolver.resolve(packageJson);
        boolean nodeModulesExists = isSafeDirectory(projectRoot.resolve("node_modules"));
        boolean distExists = isSafeDirectory(projectRoot.resolve("dist"));
        boolean dependencyCached = nodeModulesExists
                && currentSnapshot.dependencyFingerprint().equals(persistedState.dependencyFingerprint());
        boolean dependencyChanged = !currentSnapshot.dependencyFingerprint()
                .equals(persistedState.dependencyFingerprint());
        boolean criticalUnchanged = currentSnapshot.criticalFingerprint()
                .equals(persistedState.criticalFingerprint());
        boolean presentationUnchanged = currentSnapshot.presentationFingerprint()
                .equals(persistedState.presentationFingerprint());
        boolean dependencyOnlyChanged = dependencyChanged && criticalUnchanged && presentationUnchanged && distExists;

        log.info("Vue 验证计划: dependencyCached={}, dependencyOnlyChanged={}, criticalUnchanged={}, "
                        + "presentationUnchanged={}, distExists={}, lightValidate={}, lightBuild={}",
                dependencyCached,
                dependencyOnlyChanged,
                criticalUnchanged,
                presentationUnchanged,
                distExists,
                scripts.supportsLightValidation(),
                scripts.supportsLightBuild());

        if (dependencyCached && criticalUnchanged && presentationUnchanged && distExists) {
            log.info("依赖和源码均未变化，复用现有 dist: {}", normalizedProjectPath);
            return BuildExecution.completed(projectRoot, currentSnapshot, VueBuildResult.reused(normalizedProjectPath));
        }

        executionContextService.consumeIfPresent(taskId, GenerationBudgetKind.BUILD_EXECUTION);
        VueBuildCommandResult installResult = commandService.installDependencies(
                projectRoot,
                dependencyCached,
                currentSnapshot.dependencyFingerprint(),
                taskId
        );
        if (!installResult.success()) {
            log.error("pnpm install 执行失败: {}", normalizedProjectPath);
            return BuildExecution.completed(
                    projectRoot,
                    currentSnapshot,
                    VueBuildResult.installFailed(normalizedProjectPath, installResult)
            );
        }

        boolean usePresentationLightBuild = dependencyCached
                && criticalUnchanged
                && !presentationUnchanged
                && distExists
                && scripts.supportsLightBuild();
        boolean useDependencyRefreshBuild = dependencyOnlyChanged && scripts.supportsLightBuild();
        if (usePresentationLightBuild || useDependencyRefreshBuild) {
            return executeLightBuild(
                    projectRoot,
                    normalizedProjectPath,
                    taskId,
                    scripts,
                    currentSnapshot,
                    installResult,
                    useDependencyRefreshBuild
            );
        }

        return executeFullBuild(
                projectRoot,
                normalizedProjectPath,
                taskId,
                scripts,
                currentSnapshot,
                installResult
        );
    }

    private BuildExecution executeLightBuild(
            Path projectRoot,
            String projectPath,
            String taskId,
            VueProjectScripts scripts,
            VueProjectSnapshot snapshot,
            VueBuildCommandResult installResult,
            boolean dependencyRefresh
    ) {
        VueBuildCommandResult validateResult = commandService.executeLightValidation(projectRoot, scripts, taskId);
        if (!validateResult.success()) {
            log.error("Vue 轻量校验执行失败: {}", projectPath);
            return BuildExecution.completed(
                    projectRoot,
                    snapshot,
                    VueBuildResult.lightValidateFailed(projectPath, installResult, validateResult)
            );
        }

        VueBuildCommandResult buildResult = commandService.executeLightBuild(projectRoot, scripts, taskId);
        if (!buildResult.success()) {
            log.error("Vue 轻量构建执行失败: {}", projectPath);
            return BuildExecution.completed(
                    projectRoot,
                    snapshot,
                    VueBuildResult.lightBuildFailed(projectPath, installResult, buildResult)
            );
        }
        if (!isSafeDirectory(projectRoot.resolve("dist"))) {
            log.error("Vue 轻量构建完成但 dist 目录未生成: {}", projectPath);
            return BuildExecution.completed(
                    projectRoot,
                    snapshot,
                    VueBuildResult.distMissing(projectPath, installResult, buildResult)
            );
        }

        persistBuildState(projectRoot, snapshot);
        VueBuildResult result = dependencyRefresh
                ? VueBuildResult.dependencyRefreshSuccess(projectPath, installResult, buildResult)
                : VueBuildResult.lightSuccess(projectPath, installResult, buildResult);
        log.info("Vue 项目轻量构建成功: {}", projectPath);
        return BuildExecution.completed(projectRoot, snapshot, result);
    }

    private BuildExecution executeFullBuild(
            Path projectRoot,
            String projectPath,
            String taskId,
            VueProjectScripts scripts,
            VueProjectSnapshot snapshot,
            VueBuildCommandResult installResult
    ) {
        VueBuildCommandResult buildResult = commandService.executeFullBuild(projectRoot, scripts, taskId);
        if (!buildResult.success()) {
            log.error("Vue 全量构建执行失败: {}", projectPath);
            return BuildExecution.completed(
                    projectRoot,
                    snapshot,
                    VueBuildResult.buildFailed(projectPath, installResult, buildResult)
            );
        }
        if (!isSafeDirectory(projectRoot.resolve("dist"))) {
            log.error("Vue 构建完成但 dist 目录未生成: {}", projectPath);
            return BuildExecution.completed(
                    projectRoot,
                    snapshot,
                    VueBuildResult.distMissing(projectPath, installResult, buildResult)
            );
        }

        persistBuildState(projectRoot, snapshot);
        log.info("Vue 项目全量构建成功: {}", projectPath);
        return BuildExecution.completed(
                projectRoot,
                snapshot,
                VueBuildResult.success(projectPath, installResult, buildResult)
        );
    }

    private JSONObject readPackageJson(Path packageJsonFile) throws Exception {
        return JSONUtil.parseObj(Files.readString(packageJsonFile, StandardCharsets.UTF_8));
    }

    private void persistBuildState(Path projectRoot, VueProjectSnapshot snapshot) {
        try {
            stateStore.persist(projectRoot, snapshot);
        } catch (Exception exception) {
            // 构建产物已经通过校验，状态写入失败只影响后续缓存命中，不应把成功构建降级为失败。
            log.warn("写入 Vue 构建状态失败: {}, {}", projectRoot, LogExceptionSanitizer.sanitizeMessage(exception));
        }
    }

    private Path resolveProjectRoot(String projectPath) {
        if (StrUtil.isBlank(projectPath)) {
            return null;
        }
        try {
            Path projectRoot = Path.of(projectPath).toAbsolutePath().normalize();
            if (!isSafeDirectory(projectRoot)) {
                return null;
            }
            return projectRoot;
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private boolean isSafeDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    }

    private boolean isSafeRegularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
    }

    private record BuildExecution(Path projectRoot, VueProjectSnapshot snapshot, VueBuildResult result) {

        private static BuildExecution invalid(String projectPath, String summary) {
            return new BuildExecution(null, null, VueBuildResult.invalid(projectPath, summary));
        }

        private static BuildExecution completed(
                Path projectRoot,
                VueProjectSnapshot snapshot,
                VueBuildResult result
        ) {
            return new BuildExecution(projectRoot, snapshot, result);
        }
    }
}
