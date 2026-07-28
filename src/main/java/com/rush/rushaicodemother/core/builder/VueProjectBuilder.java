package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

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
        return buildProjectWithResult(projectPath, null);
    }

    /** 在生成任务上下文中构建 Vue 项目并返回结构化结果。 */
    public VueBuildResult buildProjectWithResult(String projectPath, String taskId) {
        return buildProjectWithResult(
                projectPath,
                taskId,
                BuildExecutionBudgetReservation.forTask(executionContextService, taskId)
        );
    }

    /** 在一轮组合质量门禁共享的预算预留下构建 Vue 项目。 */
    public VueBuildResult buildProjectWithResult(
            String projectPath,
            String taskId,
            BuildExecutionBudgetReservation budgetReservation
    ) {
        return executeCoordinatedBuild(projectPath, taskId, budgetReservation);
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
            VueBuildResult recentResult = resultRegistry.find(projectRoot, snapshot);
            return recentResult != null && canReuseValidatedResult(projectRoot, snapshot, null)
                    ? recentResult
                    : null;
        } catch (Exception exception) {
            log.debug("读取最近 Vue 构建结果失败: {}, {}", projectPath, LogExceptionSanitizer.sanitizeMessage(exception));
            return null;
        }
    }

    /** 执行{@code Coordinated}构建处理流程。 */
    private VueBuildResult executeCoordinatedBuild(
            String projectPath,
            String taskId,
            BuildExecutionBudgetReservation budgetReservation
    ) {
        Objects.requireNonNull(budgetReservation, "构建预算预留不能为空");
        Path projectRoot = resolveProjectRoot(projectPath);
        if (projectRoot == null) {
            log.error("Vue 项目目录不存在或无效: {}", projectPath);
            return VueBuildResult.invalid(projectPath, "项目目录不存在或无效");
        }

        String normalizedProjectPath = projectRoot.toString();
        Path packageJsonFile = projectRoot.resolve("package.json");
        if (!isSafeRegularFile(packageJsonFile)) {
            log.error("Vue 项目目录中没有安全的 package.json 文件: {}", normalizedProjectPath);
            return VueBuildResult.invalid(normalizedProjectPath, "项目目录中没有 package.json 文件");
        }

        log.info("开始构建 Vue 项目: {}", normalizedProjectPath);
        JSONObject packageJson;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            packageJson = readPackageJson(packageJsonFile);
        } catch (Exception exception) {
            log.error("package.json 解析失败: {}", LogExceptionSanitizer.sanitizeMessage(exception), LogExceptionSanitizer.sanitize(exception));
            return VueBuildResult.invalid(normalizedProjectPath, "package.json 解析失败");
        }

        VueProjectSnapshot currentSnapshot;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            currentSnapshot = snapshotService.capture(
                    projectRoot,
                    packageJson,
                    () -> executionContextService.assertCanContinue(taskId)
            );
        } catch (GenerationExecutionPolicyException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("Vue 项目指纹计算失败: {}", LogExceptionSanitizer.sanitizeMessage(exception), LogExceptionSanitizer.sanitize(exception));
            return VueBuildResult.invalid(normalizedProjectPath, "项目指纹计算失败");
        }

        VueBuildResult result = resultRegistry.execute(
                taskId,
                projectRoot,
                currentSnapshot,
                () -> canReuseValidatedResult(projectRoot, currentSnapshot, taskId),
                () -> executeStableBuild(
                        projectRoot,
                        normalizedProjectPath,
                        taskId,
                        budgetReservation,
                        packageJson,
                        currentSnapshot
                )
        );
        if ("task-reuse".equals(result.stage())) {
            log.info("复用本任务内已通过的 Vue 构建结果: taskId={}, projectRoot={}", taskId, projectRoot);
        }
        return result;
    }

    /** 执行稳定构建处理流程。 */
    private VueBuildResult executeStableBuild(
            Path projectRoot,
            String projectPath,
            String taskId,
            BuildExecutionBudgetReservation budgetReservation,
            JSONObject packageJson,
            VueProjectSnapshot expectedSnapshot
    ) {
        VueBuildResult result = executeBuild(
                projectRoot,
                projectPath,
                taskId,
                budgetReservation,
                packageJson,
                expectedSnapshot
        );
        if (!result.success()) {
            return result;
        }

        VueProjectSnapshot completedSnapshot;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            completedSnapshot = captureCurrentSnapshot(projectRoot, taskId);
        } catch (GenerationExecutionPolicyException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Vue 构建完成后无法校验源码快照: projectRoot={}, error={}",
                    projectRoot, LogExceptionSanitizer.sanitizeMessage(exception));
            return VueBuildResult.sourceChangedDuringBuild(projectPath);
        }
        if (!expectedSnapshot.equals(completedSnapshot)) {
            log.warn("Vue 项目在构建验证期间发生变化，不记录成功结果: taskId={}, projectRoot={}",
                    taskId, projectRoot);
            return VueBuildResult.sourceChangedDuringBuild(projectPath);
        }

        if (!"reuse".equals(result.stage())) {
            persistBuildState(projectRoot, expectedSnapshot);
        }
        log.info("Vue 项目构建验证成功: taskId={}, projectRoot={}, stage={}",
                taskId, projectRoot, result.stage());
        return result;
    }

    /** 执行构建处理流程。 */
    private VueBuildResult executeBuild(
            Path projectRoot,
            String normalizedProjectPath,
            String taskId,
            BuildExecutionBudgetReservation budgetReservation,
            JSONObject packageJson,
            VueProjectSnapshot currentSnapshot
    ) {

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
            return VueBuildResult.reused(normalizedProjectPath);
        }

        budgetReservation.reserve();
        VueBuildCommandResult installResult = commandService.installDependencies(
                projectRoot,
                dependencyCached,
                currentSnapshot.dependencyFingerprint(),
                taskId
        );
        if (!installResult.success()) {
            log.error("pnpm install 执行失败: {}", normalizedProjectPath);
            return VueBuildResult.installFailed(normalizedProjectPath, installResult);
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
                    installResult,
                    useDependencyRefreshBuild
            );
        }

        return executeFullBuild(
                projectRoot,
                normalizedProjectPath,
                taskId,
                scripts,
                installResult
        );
    }

    /** 执行{@code Light}构建处理流程。 */
    private VueBuildResult executeLightBuild(
            Path projectRoot,
            String projectPath,
            String taskId,
            VueProjectScripts scripts,
            VueBuildCommandResult installResult,
            boolean dependencyRefresh
    ) {
        VueBuildCommandResult validateResult = commandService.executeLightValidation(projectRoot, scripts, taskId);
        if (!validateResult.success()) {
            log.error("Vue 轻量校验执行失败: {}", projectPath);
            return VueBuildResult.lightValidateFailed(projectPath, installResult, validateResult);
        }

        VueBuildCommandResult buildResult = commandService.executeLightBuild(projectRoot, scripts, taskId);
        if (!buildResult.success()) {
            log.error("Vue 轻量构建执行失败: {}", projectPath);
            return VueBuildResult.lightBuildFailed(projectPath, installResult, buildResult);
        }
        if (!isSafeDirectory(projectRoot.resolve("dist"))) {
            log.error("Vue 轻量构建完成但 dist 目录未生成: {}", projectPath);
            return VueBuildResult.distMissing(projectPath, installResult, buildResult);
        }

        VueBuildResult result = dependencyRefresh
                ? VueBuildResult.dependencyRefreshSuccess(projectPath, installResult, buildResult)
                : VueBuildResult.lightSuccess(projectPath, installResult, buildResult);
        return result;
    }

    /** 执行全构建处理流程。 */
    private VueBuildResult executeFullBuild(
            Path projectRoot,
            String projectPath,
            String taskId,
            VueProjectScripts scripts,
            VueBuildCommandResult installResult
    ) {
        VueBuildCommandResult buildResult = commandService.executeFullBuild(projectRoot, scripts, taskId);
        if (!buildResult.success()) {
            log.error("Vue 全量构建执行失败: {}", projectPath);
            return VueBuildResult.buildFailed(projectPath, installResult, buildResult);
        }
        if (!isSafeDirectory(projectRoot.resolve("dist"))) {
            log.error("Vue 构建完成但 dist 目录未生成: {}", projectPath);
            return VueBuildResult.distMissing(projectPath, installResult, buildResult);
        }

        return VueBuildResult.success(projectPath, installResult, buildResult);
    }

    /** 判断当前状态是否允许{@code Reuse}{@code Validated}结果。 */
    private boolean canReuseValidatedResult(
            Path projectRoot,
            VueProjectSnapshot expectedSnapshot,
            String taskId
    ) {
        try {
            if (!isSafeDirectory(projectRoot.resolve("node_modules"))
                    || !isSafeDirectory(projectRoot.resolve("dist"))) {
                return false;
            }
            VueBuildState state = stateStore.read(projectRoot);
            if (!expectedSnapshot.dependencyFingerprint().equals(state.dependencyFingerprint())
                    || !expectedSnapshot.criticalFingerprint().equals(state.criticalFingerprint())
                    || !expectedSnapshot.presentationFingerprint().equals(state.presentationFingerprint())) {
                return false;
            }
            return expectedSnapshot.equals(captureCurrentSnapshot(projectRoot, taskId));
        } catch (GenerationExecutionPolicyException exception) {
            throw exception;
        } catch (Exception exception) {
            log.debug("Vue 构建结果复用校验失败，将执行真实构建: projectRoot={}, error={}",
                    projectRoot, LogExceptionSanitizer.sanitizeMessage(exception));
            return false;
        }
    }

    private VueProjectSnapshot captureCurrentSnapshot(Path projectRoot, String taskId) throws Exception {
        Path packageJsonFile = projectRoot.resolve("package.json");
        if (!isSafeRegularFile(packageJsonFile)) {
            throw new IllegalStateException("Vue 项目缺少安全的 package.json 文件");
        }
        return snapshotService.capture(
                projectRoot,
                readPackageJson(packageJsonFile),
                () -> executionContextService.assertCanContinue(taskId)
        );
    }

    private JSONObject readPackageJson(Path packageJsonFile) throws Exception {
        return JSONUtil.parseObj(Files.readString(packageJsonFile, StandardCharsets.UTF_8));
    }

    /** 持久化构建状态。 */
    private void persistBuildState(Path projectRoot, VueProjectSnapshot snapshot) {
        try {
            stateStore.persist(projectRoot, snapshot);
        } catch (Exception exception) {
            // 构建产物已经通过校验，状态写入失败只影响后续缓存命中，不应把成功构建降级为失败。
            log.warn("写入 Vue 构建状态失败: {}, {}", projectRoot, LogExceptionSanitizer.sanitizeMessage(exception));
        }
    }

    /** 根据当前上下文解析项目根。 */
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

}
