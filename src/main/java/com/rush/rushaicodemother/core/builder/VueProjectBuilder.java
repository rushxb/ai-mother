package com.rush.rushaicodemother.core.builder;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandExecutor;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandResult;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.service.dependency.DependencyInstallResult;
import com.rush.rushaicodemother.service.dependency.ProjectDependencyInstaller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 构建 Vue 项目
 */
@Slf4j
@Component
public class VueProjectBuilder {

    private static final String INSTALL_STAMP_FILE = ".ai-code-install.stamp";
    private static final String CRITICAL_STAMP_FILE = ".ai-code-critical.stamp";
    private static final String PRESENTATION_STAMP_FILE = ".ai-code-presentation.stamp";

    private final Map<String, BuildResult> recentBuildResults = new ConcurrentHashMap<>();
    private final ProjectDependencyInstaller projectDependencyInstaller;
    private final GenerationPerformanceMonitorService generationPerformanceMonitorService;
    private final ProjectCommandExecutor projectCommandExecutor;
    private final ProjectCommandProperties projectCommandProperties;
    private final GenerationExecutionContextService executionContextService;

    public VueProjectBuilder(
            ProjectDependencyInstaller projectDependencyInstaller,
            GenerationPerformanceMonitorService generationPerformanceMonitorService,
            ProjectCommandExecutor projectCommandExecutor,
            ProjectCommandProperties projectCommandProperties,
            GenerationExecutionContextService executionContextService
    ) {
        this.projectDependencyInstaller = projectDependencyInstaller;
        this.generationPerformanceMonitorService = generationPerformanceMonitorService;
        this.projectCommandExecutor = projectCommandExecutor;
        this.projectCommandProperties = projectCommandProperties;
        this.executionContextService = executionContextService;
    }

    /**
     * 异步构建 Vue 项目
     *
     * @param projectPath
     */
    public void buildProjectAsync(String projectPath) {
        Thread.ofVirtual().name("vue-builder-" + System.currentTimeMillis())
                .start(() -> {
                    try {
                        buildProject(projectPath);
                    } catch (Exception e) {
                        log.error("异步构建 Vue 项目时发生异常: {}", e.getMessage(), e);
                    }
                });
    }

    /**
     * 构建 Vue 项目
     *
     * @param projectPath 项目根目录路径
     * @return 是否构建成功
     */
    public boolean buildProject(String projectPath) {
        return buildProjectWithResult(projectPath).success();
    }

    /**
     * 构建 Vue 项目并返回详细结果
     *
     * @param projectPath 项目根目录路径
     * @return 详细构建结果
     */
    public BuildResult buildProjectWithResult(String projectPath) {
        BuildResult buildResult = doBuildProjectWithResult(projectPath, null);
        rememberBuildResult(buildResult);
        return buildResult;
    }

    public BuildResult buildProjectWithResult(String projectPath, String taskId) {
        BuildResult buildResult = doBuildProjectWithResult(projectPath, taskId);
        rememberBuildResult(buildResult);
        return buildResult;
    }

    public BuildResult getRecentBuildResult(String projectPath) {
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            return null;
        }
        File packageJsonFile = new File(projectDir, "package.json");
        if (!packageJsonFile.exists()) {
            return null;
        }
        try {
            JSONObject packageJson = JSONUtil.parseObj(Files.readString(packageJsonFile.toPath(), StandardCharsets.UTF_8));
            ProjectSnapshot currentSnapshot = captureProjectSnapshot(projectDir, packageJson);
            return recentBuildResults.get(buildCacheKey(projectDir, currentSnapshot));
        } catch (Exception e) {
            log.debug("读取最近构建结果失败: {}, {}", projectPath, e.getMessage());
            return null;
        }
    }

    private BuildResult doBuildProjectWithResult(String projectPath, String taskId) {
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            log.error("项目目录不存在：{}", projectPath);
            return BuildResult.invalid(projectPath, "项目目录不存在");
        }
        File packageJsonFile = new File(projectDir, "package.json");
        if (!packageJsonFile.exists()) {
            log.error("项目目录中没有 package.json 文件：{}", projectPath);
            return BuildResult.invalid(projectPath, "项目目录中没有 package.json 文件");
        }

        log.info("开始构建 Vue 项目：{}", projectPath);

        JSONObject packageJson;
        try {
            packageJson = JSONUtil.parseObj(Files.readString(packageJsonFile.toPath(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("package.json 解析失败：{}", e.getMessage(), e);
            return BuildResult.invalid(projectPath, "package.json 解析失败");
        }

        ProjectSnapshot currentSnapshot;
        try {
            currentSnapshot = captureProjectSnapshot(projectDir, packageJson);
        } catch (Exception e) {
            log.error("项目指纹计算失败：{}", e.getMessage(), e);
            return BuildResult.invalid(projectPath, "项目指纹计算失败");
        }

        ProjectState persistedState = readPersistedState(projectDir);
        ProjectScripts scripts = readProjectScripts(packageJson);
        boolean nodeModulesExists = isDirectory(new File(projectDir, "node_modules"));
        boolean distExists = isDirectory(new File(projectDir, "dist"));
        boolean dependencyCached = nodeModulesExists
                && currentSnapshot.dependencyFingerprint().equals(persistedState.dependencyFingerprint());
        boolean dependencyChanged = !currentSnapshot.dependencyFingerprint().equals(persistedState.dependencyFingerprint());
        boolean criticalUnchanged = currentSnapshot.criticalFingerprint().equals(persistedState.criticalFingerprint());
        boolean presentationUnchanged = currentSnapshot.presentationFingerprint().equals(persistedState.presentationFingerprint());
        boolean dependencyOnlyChanged = dependencyChanged && criticalUnchanged && presentationUnchanged && distExists;

        log.info("Vue 验证计划：dependencyCached={}, dependencyOnlyChanged={}, criticalUnchanged={}, presentationUnchanged={}, distExists={}, lightValidate={}, lightBuild={}",
                dependencyCached, dependencyOnlyChanged, criticalUnchanged, presentationUnchanged, distExists,
                scripts.supportsLightValidation(), scripts.supportsLightBuild());

        if (dependencyCached && criticalUnchanged && presentationUnchanged && distExists) {
            log.info("依赖和源码均未变化，复用现有 dist: {}", projectPath);
            CommandResult installResult = CommandResult.skipped("pnpm install --prefer-offline", "依赖和源码未变化，已跳过 pnpm install");
            CommandResult buildResult = CommandResult.skipped("reuse dist", "依赖和源码未变化，复用现有 dist");
            return BuildResult.reused(projectPath, installResult, buildResult);
        }

        executionContextService.consumeIfPresent(taskId, GenerationBudgetKind.BUILD_EXECUTION);

        CommandResult installResult = installDependenciesIfNeeded(projectDir, currentSnapshot.dependencyFingerprint(), taskId);
        if (!installResult.success()) {
            log.error("pnpm install 执行失败：{}", projectPath);
            return BuildResult.installFailed(projectPath, installResult);
        }

        boolean useLightBuild = dependencyCached && criticalUnchanged && !presentationUnchanged
                && distExists && scripts.supportsLightBuild();
        boolean useDependencyRefreshBuild = dependencyOnlyChanged && scripts.supportsLightBuild();
        if (useLightBuild || useDependencyRefreshBuild) {
            CommandResult validateResult = executeLightValidation(projectDir, scripts, taskId);
            if (!validateResult.success()) {
                log.error("轻量校验执行失败：{}", projectPath);
                return BuildResult.lightValidateFailed(projectPath, installResult, validateResult);
            }
            CommandResult buildResult = executeLightBuild(projectDir, scripts, taskId);
            if (!buildResult.success()) {
                log.error("轻量构建执行失败：{}", projectPath);
                return BuildResult.lightBuildFailed(projectPath, installResult, buildResult);
            }
            if (!isDirectory(new File(projectDir, "dist"))) {
                log.error("轻量构建完成但 dist 目录未生成：{}", projectPath);
                return BuildResult.distMissing(projectPath, installResult, buildResult);
            }
            persistProjectState(projectDir, currentSnapshot);
            if (useDependencyRefreshBuild) {
                log.info("Vue 项目依赖刷新轻量构建成功，dist 目录：{}", projectPath);
                return BuildResult.dependencyRefreshSuccess(projectPath, installResult, buildResult);
            }
            log.info("Vue 项目轻量构建成功，dist 目录：{}", projectPath);
            return BuildResult.lightSuccess(projectPath, installResult, buildResult);
        }

        CommandResult buildResult = executeFullBuild(projectDir, scripts, taskId);
        if (!buildResult.success()) {
            log.error("pnpm run build 执行失败：{}", projectPath);
            return BuildResult.buildFailed(projectPath, installResult, buildResult);
        }
        if (!isDirectory(new File(projectDir, "dist"))) {
            log.error("构建完成但 dist 目录未生成：{}", projectPath);
            return BuildResult.distMissing(projectPath, installResult, buildResult);
        }
        persistProjectState(projectDir, currentSnapshot);
        log.info("Vue 项目构建成功，dist 目录：{}", projectPath);
        return BuildResult.success(projectPath, installResult, buildResult);
    }

    private CommandResult installDependenciesIfNeeded(File projectDir, String currentDependencyFingerprint, String taskId) {
        try {
            File stampFile = new File(projectDir, INSTALL_STAMP_FILE);
            if (stampFile.exists()) {
                String installedFingerprint = readStamp(stampFile);
                if (currentDependencyFingerprint.equals(installedFingerprint)) {
                    log.info("依赖未变化，跳过 pnpm install: {}", projectDir.getAbsolutePath());
                    return CommandResult.skipped("pnpm install", "依赖未变化，已跳过 pnpm install");
                }
            }
            GenerationPerformanceMonitorService.SpanTimer span =
                    generationPerformanceMonitorService.startSpan(taskId, "pnpm_install");
            try {
                DependencyInstallResult result = projectDependencyInstaller.ensureInstalled(projectDir.toPath(), taskId);
                if (result.success()) {
                    writeStamp(stampFile, currentDependencyFingerprint);
                    span.success();
                    return CommandResult.success("pnpm install", 0, result.output());
                }
                span.failed(result.errorDetail());
                return CommandResult.failed("pnpm install", 1, result.output());
            } catch (Exception e) {
                span.failed(e.getMessage());
                throw e;
            }
        } catch (GenerationExecutionPolicyException e) {
            throw e;
        } catch (Exception e) {
            log.warn("依赖缓存判断失败，将执行 pnpm install: {}", e.getMessage());
            GenerationPerformanceMonitorService.SpanTimer span =
                    generationPerformanceMonitorService.startSpan(taskId, "pnpm_install");
            try {
                DependencyInstallResult result = projectDependencyInstaller.ensureInstalled(projectDir.toPath(), taskId);
                if (result.success()) {
                    span.success();
                    return CommandResult.success("pnpm install", 0, result.output());
                }
                span.failed(result.errorDetail());
                return CommandResult.failed("pnpm install", 1, result.output());
            } catch (GenerationExecutionPolicyException retryException) {
                span.failed(retryException.getMessage());
                throw retryException;
            } catch (Exception retryException) {
                span.failed(retryException.getMessage());
                return CommandResult.exception("pnpm install", retryException.getMessage());
            }
        }
    }

    private ProjectSnapshot captureProjectSnapshot(File projectDir, JSONObject packageJson) throws Exception {
        List<String> dependencyEntries = new ArrayList<>();
        List<String> criticalEntries = new ArrayList<>();
        List<String> presentationEntries = new ArrayList<>();

        appendPackageDependencyFingerprint(dependencyEntries, packageJson);
        appendPackageScriptFingerprint(criticalEntries, packageJson);

        Path rootPath = projectDir.toPath();
        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(rootPath) && shouldSkipDirectory(rootPath.relativize(dir))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                Path relativePath = rootPath.relativize(file);
                String normalized = normalizePath(relativePath);
                if (isInternalStateFile(normalized) || normalized.equals("package.json")) {
                    return FileVisitResult.CONTINUE;
                }
                if (isDependencyLockFile(normalized)) {
                    appendFileFingerprint(dependencyEntries, relativePath, file);
                    return FileVisitResult.CONTINUE;
                }
                if (isPresentationFile(normalized)) {
                    appendFileFingerprint(presentationEntries, relativePath, file);
                    return FileVisitResult.CONTINUE;
                }
                if (isCriticalFile(normalized)) {
                    appendFileFingerprint(criticalEntries, relativePath, file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return new ProjectSnapshot(
                hashEntries(dependencyEntries),
                hashEntries(criticalEntries),
                hashEntries(presentationEntries)
        );
    }

    private void appendPackageDependencyFingerprint(List<String> entries, JSONObject packageJson) {
        appendJsonSectionFingerprint(entries, "dependencies", packageJson.getJSONObject("dependencies"));
        appendJsonSectionFingerprint(entries, "devDependencies", packageJson.getJSONObject("devDependencies"));
        appendJsonSectionFingerprint(entries, "peerDependencies", packageJson.getJSONObject("peerDependencies"));
        appendJsonSectionFingerprint(entries, "optionalDependencies", packageJson.getJSONObject("optionalDependencies"));
        appendJsonSectionFingerprint(entries, "overrides", packageJson.getJSONObject("overrides"));
        appendJsonSectionFingerprint(entries, "resolutions", packageJson.getJSONObject("resolutions"));
    }

    private void appendPackageScriptFingerprint(List<String> entries, JSONObject packageJson) {
        appendJsonSectionFingerprint(entries, "scripts", packageJson.getJSONObject("scripts"));
        appendStringFingerprint(entries, "packageManager", packageJson.getStr("packageManager"));
    }

    private void appendJsonSectionFingerprint(List<String> entries, String sectionName, JSONObject section) {
        if (section == null || section.isEmpty()) {
            entries.add(sectionName + ":{}");
            return;
        }
        List<String> keys = new ArrayList<>(section.keySet());
        Collections.sort(keys);
        StringBuilder builder = new StringBuilder(sectionName).append('{');
        for (String key : keys) {
            builder.append(key).append('=').append(StrUtil.nullToEmpty(section.getStr(key))).append(';');
        }
        builder.append('}');
        entries.add(builder.toString());
    }

    private void appendStringFingerprint(List<String> entries, String key, String value) {
        entries.add(key + ':' + StrUtil.nullToEmpty(value));
    }

    private void appendFileFingerprint(List<String> entries, Path relativePath, Path filePath) throws java.io.IOException {
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return;
        }
        byte[] content = Files.readAllBytes(filePath);
        entries.add(normalizePath(relativePath) + ':' + content.length + ':' + Arrays.hashCode(content));
    }

    private CommandResult executeLightValidation(File projectDir, ProjectScripts scripts, String taskId) {
        String script = scripts.lightValidationScript();
        if (script == null) {
            return CommandResult.skipped("light validation", "package.json 中未找到轻量校验脚本");
        }
        log.info("执行轻量校验: pnpm run {}", script);
        return executeCommand(
                "light_validate",
                taskId,
                projectDir,
                script,
                projectCommandProperties.getLightValidationTimeout()
        );
    }

    private CommandResult executeLightBuild(File projectDir, ProjectScripts scripts, String taskId) {
        String script = scripts.lightBuildScript();
        if (script == null) {
            return CommandResult.exception("pnpm run build", "package.json 中未找到可用的轻量构建脚本");
        }
        log.info("执行轻量构建: pnpm run {}", script);
        return executeCommand(
                "light_build",
                taskId,
                projectDir,
                script,
                projectCommandProperties.getLightBuildTimeout()
        );
    }

    private CommandResult executeFullBuild(File projectDir, ProjectScripts scripts, String taskId) {
        String script = scripts.fullBuildScript();
        if (script == null) {
            return CommandResult.exception("pnpm run build", "package.json 中未找到可用的构建脚本");
        }
        log.info("执行全量构建: pnpm run {}", script);
        return executeCommand(
                "full_build",
                taskId,
                projectDir,
                script,
                projectCommandProperties.getFullBuildTimeout()
        );
    }

    private CommandResult executeCommand(
            String performanceStage,
            String taskId,
            File workingDir,
            String script,
            java.time.Duration timeout
    ) {
        GenerationPerformanceMonitorService.SpanTimer span =
                generationPerformanceMonitorService.startSpan(taskId, performanceStage);
        try {
            ProjectCommandResult result = projectCommandExecutor.executePnpmScript(
                    workingDir.toPath(),
                    script,
                    timeout,
                    taskId,
                    performanceStage + ":" + workingDir.getAbsolutePath()
            );
            if (result.success()) {
                span.success();
                return CommandResult.success(result.command(), result.exitCode() == null ? 0 : result.exitCode(), result.output());
            }
            span.close(result.timedOut() ? "timeout" : "failed", result.command());
            return CommandResult.fromProjectCommand(result);
        } catch (GenerationExecutionPolicyException exception) {
            span.failed(exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            span.failed(exception.getMessage());
            return CommandResult.exception("pnpm run " + script, exception.getMessage());
        }
    }

    private ProjectState readPersistedState(File projectDir) {
        return new ProjectState(
                readStamp(new File(projectDir, INSTALL_STAMP_FILE)),
                readStamp(new File(projectDir, CRITICAL_STAMP_FILE)),
                readStamp(new File(projectDir, PRESENTATION_STAMP_FILE))
        );
    }

    private void persistProjectState(File projectDir, ProjectSnapshot snapshot) {
        try {
            writeStamp(new File(projectDir, INSTALL_STAMP_FILE), snapshot.dependencyFingerprint());
            writeStamp(new File(projectDir, CRITICAL_STAMP_FILE), snapshot.criticalFingerprint());
            writeStamp(new File(projectDir, PRESENTATION_STAMP_FILE), snapshot.presentationFingerprint());
        } catch (Exception e) {
            log.warn("写入构建指纹失败: {}", e.getMessage());
        }
    }

    private void rememberBuildResult(BuildResult buildResult) {
        if (buildResult == null || StrUtil.isBlank(buildResult.projectPath())) {
            return;
        }
        File projectDir = new File(buildResult.projectPath());
        File packageJsonFile = new File(projectDir, "package.json");
        if (!packageJsonFile.exists()) {
            return;
        }
        try {
            JSONObject packageJson = JSONUtil.parseObj(Files.readString(packageJsonFile.toPath(), StandardCharsets.UTF_8));
            ProjectSnapshot snapshot = captureProjectSnapshot(projectDir, packageJson);
            recentBuildResults.put(buildCacheKey(projectDir, snapshot), buildResult);
        } catch (Exception e) {
            log.debug("记录最近构建结果失败: {}, {}", buildResult.projectPath(), e.getMessage());
        }
    }

    private String buildCacheKey(File projectDir, ProjectSnapshot snapshot) {
        return projectDir.getAbsolutePath()
                + "|" + snapshot.dependencyFingerprint()
                + "|" + snapshot.criticalFingerprint()
                + "|" + snapshot.presentationFingerprint();
    }

    private String readStamp(File stampFile) {
        try {
            if (!stampFile.exists()) {
                return "";
            }
            return Files.readString(stampFile.toPath(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            log.debug("读取指纹文件失败: {}, {}", stampFile.getAbsolutePath(), e.getMessage());
            return "";
        }
    }

    private void writeStamp(File stampFile, String fingerprint) throws Exception {
        Files.writeString(stampFile.toPath(), fingerprint, StandardCharsets.UTF_8);
    }

    private boolean isDirectory(File file) {
        return file.exists() && file.isDirectory();
    }

    private String normalizePath(Path relativePath) {
        return relativePath.toString().replace(File.separatorChar, '/');
    }

    private boolean shouldSkipDirectory(Path relativePath) {
        String normalized = normalizePath(relativePath).toLowerCase(Locale.ROOT);
        return isDirectoryToken(normalized, "node_modules")
                || isDirectoryToken(normalized, "dist")
                || isDirectoryToken(normalized, "coverage")
                || isDirectoryToken(normalized, "target")
                || isDirectoryToken(normalized, "build")
                || isDirectoryToken(normalized, "out")
                || isDirectoryToken(normalized, ".git")
                || isDirectoryToken(normalized, ".idea")
                || isDirectoryToken(normalized, ".vscode")
                || isDirectoryToken(normalized, ".cache")
                || isDirectoryToken(normalized, ".turbo");
    }

    private boolean isDirectoryToken(String normalizedPath, String token) {
        return normalizedPath.equals(token)
                || normalizedPath.startsWith(token + "/")
                || normalizedPath.contains("/" + token + "/");
    }

    private boolean isInternalStateFile(String normalizedPath) {
        return normalizedPath.equals(INSTALL_STAMP_FILE)
                || normalizedPath.equals(CRITICAL_STAMP_FILE)
                || normalizedPath.equals(PRESENTATION_STAMP_FILE);
    }

    private boolean isDependencyLockFile(String normalizedPath) {
        return normalizedPath.equals("package-lock.json")
                || normalizedPath.equals("pnpm-lock.yaml")
                || normalizedPath.equals("yarn.lock");
    }

    private boolean isPresentationFile(String normalizedPath) {
        return normalizedPath.equals("index.html")
                || normalizedPath.startsWith("public/")
                || normalizedPath.endsWith(".vue")
                || normalizedPath.endsWith(".css")
                || normalizedPath.endsWith(".scss")
                || normalizedPath.endsWith(".less")
                || normalizedPath.endsWith(".sass")
                || normalizedPath.endsWith(".styl")
                || normalizedPath.endsWith(".html")
                || normalizedPath.endsWith(".svg")
                || normalizedPath.endsWith(".png")
                || normalizedPath.endsWith(".jpg")
                || normalizedPath.endsWith(".jpeg")
                || normalizedPath.endsWith(".gif")
                || normalizedPath.endsWith(".webp")
                || normalizedPath.endsWith(".ico")
                || normalizedPath.endsWith(".avif")
                || normalizedPath.endsWith(".bmp");
    }

    private boolean isCriticalFile(String normalizedPath) {
        if (isDependencyLockFile(normalizedPath) || isPresentationFile(normalizedPath)) {
            return false;
        }
        if (normalizedPath.startsWith("vite.config.")
                || normalizedPath.startsWith("vue.config.")
                || normalizedPath.startsWith("tsconfig")
                || normalizedPath.startsWith("eslint.config.")
                || normalizedPath.startsWith(".eslintrc")
                || normalizedPath.startsWith("prettier.config.")
                || normalizedPath.startsWith(".prettierrc")
                || normalizedPath.startsWith("postcss.config.")
                || normalizedPath.startsWith("tailwind.config.")
                || normalizedPath.startsWith(".env")) {
            return true;
        }
        if (!normalizedPath.startsWith("src/")) {
            return false;
        }
        return normalizedPath.endsWith(".js")
                || normalizedPath.endsWith(".mjs")
                || normalizedPath.endsWith(".cjs")
                || normalizedPath.endsWith(".ts")
                || normalizedPath.endsWith(".mts")
                || normalizedPath.endsWith(".cts")
                || normalizedPath.endsWith(".jsx")
                || normalizedPath.endsWith(".tsx")
                || normalizedPath.endsWith(".d.ts")
                || normalizedPath.endsWith(".json");
    }

    private String hashEntries(List<String> entries) {
        Collections.sort(entries);
        return Integer.toHexString(String.join("\n", entries).hashCode());
    }

    private record ProjectSnapshot(String dependencyFingerprint, String criticalFingerprint,
                                   String presentationFingerprint) {
    }

    private record ProjectState(String dependencyFingerprint, String criticalFingerprint,
                                String presentationFingerprint) {
    }

    private record ProjectScripts(boolean hasBuild, boolean hasPureBuild, boolean hasBuildOnly,
                                  boolean hasTypeCheck, boolean hasTypecheck, boolean hasCheck) {

        boolean supportsLightValidation() {
            return hasTypeCheck || hasTypecheck || hasCheck;
        }

        boolean supportsLightBuild() {
            return hasPureBuild || hasBuildOnly;
        }

        String lightValidationScript() {
            if (hasTypeCheck) {
                return "type-check";
            }
            if (hasTypecheck) {
                return "typecheck";
            }
            if (hasCheck) {
                return "check";
            }
            return null;
        }

        String lightBuildScript() {
            if (hasPureBuild) {
                return "pure-build";
            }
            if (hasBuildOnly) {
                return "build-only";
            }
            return null;
        }

        String fullBuildScript() {
            if (hasBuild) {
                return "build";
            }
            return lightBuildScript();
        }
    }

    private ProjectScripts readProjectScripts(JSONObject packageJson) {
        JSONObject scripts = packageJson.getJSONObject("scripts");
        return new ProjectScripts(
                hasScript(scripts, "build"),
                hasScript(scripts, "pure-build"),
                hasScript(scripts, "build-only"),
                hasScript(scripts, "type-check"),
                hasScript(scripts, "typecheck"),
                hasScript(scripts, "check")
        );
    }

    private boolean hasScript(JSONObject scripts, String name) {
        return scripts != null && scripts.containsKey(name);
    }

    public record CommandResult(String command, boolean success, Integer exitCode, boolean timeout, String output,
                                String errorMessage) {

        private static CommandResult success(String command, int exitCode, String output) {
            return new CommandResult(command, true, exitCode, false, output, null);
        }

        private static CommandResult fromProjectCommand(ProjectCommandResult result) {
            return new CommandResult(
                    result.command(),
                    result.success(),
                    result.exitCode(),
                    result.timedOut(),
                    result.output(),
                    result.errorDetail()
            );
        }

        private static CommandResult skipped(String command, String output) {
            return new CommandResult(command, true, 0, false, output, null);
        }

        private static CommandResult failed(String command, int exitCode, String output) {
            return new CommandResult(command, false, exitCode, false, output, null);
        }

        private static CommandResult exception(String command, String errorMessage) {
            return new CommandResult(command, false, null, false, "", errorMessage);
        }

        public String toDiagnosticBlock() {
            StringBuilder builder = new StringBuilder();
            builder.append("命令: ").append(command).append('\n');
            builder.append("结果: ").append(success ? "成功" : "失败").append('\n');
            if (exitCode != null) {
                builder.append("退出码: ").append(exitCode).append('\n');
            }
            if (timeout) {
                builder.append("超时: 是").append('\n');
            }
            if (StrUtil.isNotBlank(errorMessage)) {
                builder.append("异常: ").append(errorMessage).append('\n');
            }
            builder.append("日志:\n");
            if (StrUtil.isBlank(output)) {
                builder.append("(无输出)");
            } else {
                builder.append(output.trim());
            }
            return builder.toString();
        }
    }

    public record BuildResult(boolean success, String stage, String projectPath, String summary,
                              CommandResult installResult, CommandResult buildResult) {

        private static BuildResult invalid(String projectPath, String summary) {
            return new BuildResult(false, "prepare", projectPath, summary, null, null);
        }

        private static BuildResult installFailed(String projectPath, CommandResult installResult) {
            return new BuildResult(false, "install", projectPath,
                    commandSummary(installResult, "安装失败"), installResult, null);
        }

        private static BuildResult lightBuildFailed(String projectPath, CommandResult installResult,
                                                    CommandResult buildResult) {
            return new BuildResult(false, "build-light", projectPath,
                    commandSummary(buildResult, "轻量构建失败"), installResult, buildResult);
        }

        private static BuildResult lightValidateFailed(String projectPath, CommandResult installResult,
                                                       CommandResult validateResult) {
            return new BuildResult(false, "validate-light", projectPath,
                    commandSummary(validateResult, "轻量校验失败"), installResult, validateResult);
        }

        private static BuildResult buildFailed(String projectPath, CommandResult installResult,
                                               CommandResult buildResult) {
            return new BuildResult(false, "build", projectPath,
                    commandSummary(buildResult, "全量构建失败"), installResult, buildResult);
        }

        private static BuildResult distMissing(String projectPath, CommandResult installResult,
                                               CommandResult buildResult) {
            return new BuildResult(false, "dist", projectPath, "构建完成但未生成 dist 目录", installResult, buildResult);
        }

        private static BuildResult success(String projectPath, CommandResult installResult,
                                           CommandResult buildResult) {
            return new BuildResult(true, "done", projectPath, "Vue 项目构建成功", installResult, buildResult);
        }

        private static BuildResult lightSuccess(String projectPath, CommandResult installResult,
                                                CommandResult buildResult) {
            return new BuildResult(true, "light-done", projectPath, "轻量构建通过并刷新 dist", installResult, buildResult);
        }

        private static BuildResult dependencyRefreshSuccess(String projectPath, CommandResult installResult,
                                                            CommandResult buildResult) {
            return new BuildResult(true, "dependency-refresh", projectPath, "依赖刷新后轻量构建通过", installResult, buildResult);
        }

        private static BuildResult reused(String projectPath, CommandResult installResult,
                                          CommandResult buildResult) {
            return new BuildResult(true, "reuse", projectPath, "依赖和源码未变化，复用现有 dist", installResult, buildResult);
        }

        private static String commandSummary(CommandResult result, String fallback) {
            if (result == null || StrUtil.isBlank(result.command())) {
                return fallback;
            }
            StringBuilder builder = new StringBuilder(result.command()).append(' ').append(fallback);
            if (result.exitCode() != null) {
                builder.append("，exitCode=").append(result.exitCode());
            }
            if (result.timeout()) {
                builder.append("，timeout=true");
            }
            if (StrUtil.isNotBlank(result.errorMessage())) {
                builder.append("，error=").append(result.errorMessage());
            }
            String errorSnippet = extractDiagnosticSnippet(result.output());
            if (StrUtil.isNotBlank(errorSnippet)) {
                builder.append("，日志: ").append(errorSnippet);
            }
            return builder.toString();
        }

        public String toDiagnosticReport() {
            StringBuilder builder = new StringBuilder();
            builder.append("项目路径: ").append(projectPath).append('\n');
            builder.append("构建结果: ").append(success ? "成功" : "失败").append('\n');
            builder.append("失败阶段: ").append(stage).append('\n');
            builder.append("摘要: ").append(summary).append('\n');
            builder.append("验证层级: ").append(validationTier()).append('\n');
            builder.append("修复优先级: ").append(repairPriority()).append('\n');
            builder.append("执行路径: ").append(executionPath()).append('\n');
            if (installResult != null) {
                builder.append("\n[安装阶段]\n")
                        .append(installResult.toDiagnosticBlock())
                        .append('\n');
            }
            if (buildResult != null) {
                builder.append("\n[构建阶段]\n")
                        .append(buildResult.toDiagnosticBlock())
                        .append('\n');
            }
            return builder.toString().trim();
        }

        public String toFailureSummary() {
            List<String> parts = List.of(
                    "Vue 项目构建失败",
                    "阶段: " + stage,
                    "验证层级: " + validationTier(),
                    "摘要: " + summary,
                    "修复优先级: " + repairPriority()
            );
            StringBuilder builder = new StringBuilder(String.join("，", parts));
            if (buildResult != null) {
                builder.append("。构建日志片段：").append(extractSingleLine(buildResult.output()));
            } else if (installResult != null) {
                builder.append("。安装日志片段：").append(extractSingleLine(installResult.output()));
            }
            return builder.toString();
        }

        public String validationTier() {
            return switch (stage) {
                case "reuse" -> "复用";
                case "light-done", "build-light", "validate-light", "dependency-refresh" -> "轻量";
                case "done", "build" -> "全量";
                case "install" -> "安装";
                case "dist" -> "产物检查";
                default -> "准备";
            };
        }

        public String repairPriority() {
            return switch (stage) {
                case "install", "validate-light", "build-light", "build" -> "高";
                case "dist", "prepare" -> "中";
                default -> "低";
            };
        }

        public String executionPath() {
            return switch (stage) {
                case "reuse" -> "复用现有 dist，跳过安装和构建";
                case "light-done" -> "跳过安装，执行轻量校验和轻量构建";
                case "dependency-refresh" -> "依赖刷新后执行轻量校验和轻量构建";
                case "validate-light" -> "轻量校验失败";
                case "build-light" -> "轻量构建失败";
                case "done" -> "执行全量安装和构建";
                case "build" -> "全量构建失败";
                case "install" -> "依赖安装失败";
                case "dist" -> "构建完成后校验 dist";
                case "prepare" -> "准备阶段";
                default -> stage;
            };
        }

        private String extractSingleLine(String output) {
            if (StrUtil.isBlank(output)) {
                return "无";
            }
            String normalized = output.replace("\r", " ").replace("\n", " ").trim();
            return StrUtil.sub(normalized, 0, Math.min(normalized.length(), 300));
        }

        private static String extractDiagnosticSnippet(String output) {
            if (StrUtil.isBlank(output)) {
                return "";
            }
            String[] lines = output.replace("\r\n", "\n").replace('\r', '\n').split("\n");
            List<String> usefulLines = Arrays.stream(lines)
                    .map(String::trim)
                    .filter(StrUtil::isNotBlank)
                    .filter(line -> {
                        String lower = line.toLowerCase(Locale.ROOT);
                        return lower.contains("error")
                                || lower.contains("failed")
                                || lower.contains("syntaxerror")
                                || lower.contains("referenceerror")
                                || lower.contains("typeerror")
                                || lower.contains("cannot find")
                                || lower.contains("already been declared")
                                || lower.contains("is not defined")
                                || lower.contains("does not provide an export")
                                || lower.contains("failed to resolve")
                                || lower.contains(".vue")
                                || lower.contains(".js")
                                || lower.contains(".ts");
                    })
                    .limit(12)
                    .toList();
            String snippet = usefulLines.isEmpty()
                    ? String.join("\n", Arrays.stream(lines).map(String::trim).filter(StrUtil::isNotBlank).limit(12).toList())
                    : String.join("\n", usefulLines);
            String normalized = snippet.replace("\r", " ").replace("\n", " | ").trim();
            return StrUtil.sub(normalized, 0, Math.min(normalized.length(), 2000));
        }
    }
}
