package com.rush.rushaicodemother.orchestration.snapshot;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.config.GenerationCommitProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemException;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.git.GitCommandExecutor;
import com.rush.rushaicodemother.infrastructure.git.GitCommandResult;
import com.rush.rushaicodemother.infrastructure.git.GitTransactionResourceManager;
import com.rush.rushaicodemother.infrastructure.git.GitTransactionResourceManager.GitTransactionResourceException;
import com.rush.rushaicodemother.infrastructure.git.GitTransactionResourceManager.GitTransactionResources;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationCommitResult;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.orchestration.workspace.ReportedWorkspaceResolutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;

/**
 * 生成成功后只提交本次生成工作区变更，输出本地 Git commit 元数据。
 */
@Slf4j
@Component
public class GenerationCommitService {

    private static final Duration LOCK_POLICY_CHECK_INTERVAL = Duration.ofMillis(100);

    private final GenerationOrchestrationMetricsCollector metricsCollector;
    private final GitCommandExecutor gitCommandExecutor;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final GitTransactionResourceManager transactionResourceManager;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationExecutionContextService executionContextService;
    private final ReentrantLock[] repositoryLocks;
    private final int maxFilesPerCommit;
    private final int maxPathspecBytes;

    /**
 * 创建生成提交服务实例并完成必要的依赖和初始状态设置。
 *
 * @param metricsCollector {@code metricsCollector} 对应的调用参数
 * @param gitCommandExecutor {@code gitCommandExecutor} 对应的调用参数
 * @param workspaceFileSystemService 处理该职责的领域服务
 * @param transactionResourceManager 事务资源管理器
 * @param generationWorkspaceService 生成工作区服务
 * @param properties 配置属性
 */
    public GenerationCommitService(
            GenerationOrchestrationMetricsCollector metricsCollector,
            GitCommandExecutor gitCommandExecutor,
            WorkspaceFileSystemService workspaceFileSystemService,
            GitTransactionResourceManager transactionResourceManager,
            GenerationWorkspaceService generationWorkspaceService,
            GenerationExecutionContextService executionContextService,
            GenerationCommitProperties properties
    ) {
        this.metricsCollector = Objects.requireNonNull(metricsCollector, "metricsCollector must not be null");
        this.gitCommandExecutor = Objects.requireNonNull(gitCommandExecutor, "gitCommandExecutor must not be null");
        this.workspaceFileSystemService = Objects.requireNonNull(
                workspaceFileSystemService,
                "workspaceFileSystemService must not be null"
        );
        this.transactionResourceManager = Objects.requireNonNull(
                transactionResourceManager,
                "transactionResourceManager must not be null"
        );
        this.generationWorkspaceService = Objects.requireNonNull(
                generationWorkspaceService,
                "generationWorkspaceService must not be null"
        );
        this.executionContextService = Objects.requireNonNull(
                executionContextService,
                "executionContextService must not be null"
        );
        Objects.requireNonNull(properties, "properties must not be null");
        this.repositoryLocks = createLocks(properties.getLockStripes());
        this.maxFilesPerCommit = requirePositiveLimit(
                properties.getMaxFilesPerCommit(),
                "Git max files per commit must be greater than 0"
        );
        this.maxPathspecBytes = requirePositiveLimit(
                properties.getMaxPathspecBytes(),
                "Git max pathspec bytes must be greater than 0"
        );
    }

    /**
 * 提交并返回{@code If}{@code Allowed}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param diffSummaryArtifact {@code diffSummaryArtifact} 对应的调用参数
 * @return 生成提交
 */
    public GenerationArtifact commitIfAllowed(
            Long appId,
            String taskId,
            GenerationArtifact diffSummaryArtifact
    ) {
        GenerationCommitResult result = commit(appId, taskId, diffSummaryArtifact);
        metricsCollector.recordGenerationCommit(result.provider(), result.status(), result.reason());
        return GenerationArtifact.of("generation_commit", "Orchestrator", "生成结果本地 Git 提交", result.toPayload());
    }

    /**
 * 提交并返回。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param diffSummaryArtifact {@code diffSummaryArtifact} 对应的调用参数
 * @return 生成提交
 */
    public GenerationCommitResult commit(
            Long appId,
            String taskId,
            GenerationArtifact diffSummaryArtifact
    ) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (appId == null || appId <= 0) {
            return GenerationCommitResult.skipped(appId, taskId, "", "", "", "invalid_app_id");
        }
        if (StrUtil.isBlank(taskId)) {
            return GenerationCommitResult.skipped(appId, taskId, "", "", "", "task_id_missing");
        }
        if (diffSummaryArtifact != null && !"diff_summary".equals(diffSummaryArtifact.key())) {
            return GenerationCommitResult.skipped(appId, taskId, "", "", "", "diff_summary_artifact_invalid");
        }
        Map<String, Object> diffPayload = payload(diffSummaryArtifact);
        String currentPathValue = stringValue(diffPayload.get("currentPath"));
        if (diffPayload.isEmpty()) {
            return GenerationCommitResult.skipped(appId, taskId, "", "", "", "diff_summary_missing");
        }
        if (!artifactContextMatches(appId, taskId, diffPayload)) {
            return GenerationCommitResult.skipped(
                    appId, taskId, "", "", "", "diff_summary_context_mismatch"
            );
        }
        if (!"created".equals(stringValue(diffPayload.get("status")))) {
            return GenerationCommitResult.skipped(
                    appId, taskId, currentPathValue, "", "", "diff_summary_not_created"
            );
        }
        ChangedFileSelection changedFileSelection = changedFiles(diffPayload);
        if (changedFileSelection.limitExceeded()) {
            return GenerationCommitResult.skipped(
                    appId, taskId, "", "", "", "changed_file_limit_exceeded"
            );
        }
        if (changedFileSelection.files().isEmpty()) {
            return GenerationCommitResult.skipped(appId, taskId, currentPathValue, "", "", "no_diff_files");
        }
        if (StrUtil.isBlank(currentPathValue)) {
            return GenerationCommitResult.skipped(appId, taskId, "", "", "", "current_path_missing");
        }

        Path projectPath;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            projectPath = requireSafeProjectPath(appId, currentPathValue);
        } catch (ProjectPathException exception) {
            return GenerationCommitResult.skipped(
                    appId,
                    taskId,
                    exception.reportedPath(),
                    "",
                    "",
                    exception.reason()
            );
        }

        try {
            return commitChangedFiles(appId, taskId, projectPath, changedFileSelection.files());
        } catch (GenerationExecutionPolicyException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn(
                    "生成结果本地 Git 提交失败，appId: {}, taskId: {}, exceptionType: {}",
                    appId,
                    taskId,
                    exception.getClass().getSimpleName()
            );
            return GenerationCommitResult.failed(
                    appId,
                    taskId,
                    projectPath.toString(),
                    "",
                    "",
                    "git_commit_exception"
            );
        }
    }

    /**
 * 渲染{@code Text}。
 *
 * @param result 待处理结果
 * @return 处理后的{@code Text}文本
 */
    public String renderText(GenerationCommitResult result) {
        if (result == null) {
            return "生成结果本地 Git 提交结果不可用";
        }
        if ("committed".equals(result.status())) {
            return "生成结果已提交到本地 Git: " + result.shortCommitId();
        }
        if ("failed".equals(result.status())) {
            return "生成结果本地 Git 提交失败: " + result.reason();
        }
        return "生成结果本地 Git 提交已跳过: " + result.reason();
    }

    /** 提交并返回变更文件。 */
    private GenerationCommitResult commitChangedFiles(
            Long appId,
            String taskId,
            Path projectPath,
            List<String> changedFiles
    ) throws IOException {
        GitCommandResult gitRootResult = runGit(taskId, projectPath, List.of("rev-parse", "--show-toplevel"));
        if (!gitRootResult.success()) {
            if (gitRootResult.interrupted()) {
                return interruptedResult(appId, taskId, projectPath);
            }
            if (!gitRootResult.commandCompleted()) {
                return GenerationCommitResult.failed(
                        appId,
                        taskId,
                        projectPath.toString(),
                        "",
                        "",
                        "git_root_lookup_failed"
                );
            }
            return GenerationCommitResult.skipped(
                    appId, taskId, projectPath.toString(), "", "", "git_repository_missing"
            );
        }
        Path gitRoot = resolveExactDirectory(gitRootResult.stdout(), projectPath);
        if (gitRoot == null) {
            return GenerationCommitResult.skipped(
                    appId, taskId, projectPath.toString(), "", "", "git_repository_root_mismatch"
            );
        }

        List<String> gitRelativeFiles = toGitRelativeFiles(gitRoot, projectPath, changedFiles);
        if (gitRelativeFiles.isEmpty()) {
            return GenerationCommitResult.skipped(
                    appId,
                    taskId,
                    projectPath.toString(),
                    "",
                    currentBranch(taskId, gitRoot),
                    "no_committable_files"
            );
        }

        ReentrantLock repositoryLock = lockFor(gitRoot);
        try {
            acquireRepositoryLock(repositoryLock, taskId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return GenerationCommitResult.failed(
                    appId,
                    taskId,
                    projectPath.toString(),
                    "",
                    "",
                    "git_commit_interrupted"
            );
        }
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            return commitWithRepositoryLock(
                    appId,
                    taskId,
                    projectPath,
                    gitRoot,
                    gitRelativeFiles
            );
        } finally {
            repositoryLock.unlock();
        }
    }

    /** 提交并返回并仓储锁。 */
    private GenerationCommitResult commitWithRepositoryLock(
            Long appId,
            String taskId,
            Path projectPath,
            Path gitRoot,
            List<String> gitRelativeFiles
    ) throws IOException {
        GitCommandResult gitDirectoryResult = runGit(
                taskId,
                gitRoot,
                List.of("rev-parse", "--absolute-git-dir")
        );
        Path expectedGitDirectory = gitRoot.resolve(".git").normalize();
        Path gitDirectory = gitDirectoryResult.success()
                ? resolveExactDirectory(gitDirectoryResult.stdout(), expectedGitDirectory)
                : null;
        if (gitDirectory == null) {
            if (!gitDirectoryResult.success()) {
                return failedCommandResult(
                        appId,
                        taskId,
                        projectPath,
                        gitRoot,
                        "git_directory_unavailable",
                        gitDirectoryResult
                );
            }
            return GenerationCommitResult.failed(
                    appId,
                    taskId,
                    projectPath.toString(),
                    headCommit(taskId, gitRoot),
                    currentBranch(taskId, gitRoot),
                    "git_directory_unavailable"
            );
        }

        GitTransactionResources transactionResources;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            transactionResources = transactionResourceManager.create(
                    gitDirectory,
                    gitRelativeFiles,
                    maxPathspecBytes
            );
        } catch (GitTransactionResourceException exception) {
            return GenerationCommitResult.failed(
                    appId,
                    taskId,
                    projectPath.toString(),
                    headCommit(taskId, gitRoot),
                    currentBranch(taskId, gitRoot),
                    mapTransactionResourceFailure(exception)
            );
        }
        Map<String, String> transactionEnvironment = Map.of(
                "GIT_INDEX_FILE", transactionResources.temporaryIndex().toString()
        );
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            GitCommandResult indexResult = prepareTemporaryIndex(taskId, gitRoot, transactionEnvironment);
            if (!indexResult.success()) {
                return failedCommandResult(
                        appId,
                        taskId,
                        projectPath,
                        gitRoot,
                        "git_index_prepare_failed",
                        indexResult
                );
            }

            GitCommandResult stageResult = runGit(
                    taskId,
                    gitRoot,
                    List.of(
                            "add",
                            "-A",
                            "--pathspec-from-file=" + transactionResources.temporaryPathspec(),
                            "--pathspec-file-nul"
                    ),
                    transactionEnvironment
            );
            if (!stageResult.success()) {
                return failedCommandResult(
                        appId,
                        taskId,
                        projectPath,
                        gitRoot,
                        "git_stage_failed",
                        stageResult
                );
            }

            GitCommandResult stagedResult = runGit(
                    taskId,
                    gitRoot,
                    List.of(
                            "diff",
                            "--cached",
                            "--name-only",
                            "-z",
                            "--output=" + transactionResources.temporaryStagedOutput()
                    ),
                    transactionEnvironment
            );
            if (!stagedResult.success()) {
                return failedCommandResult(
                        appId,
                        taskId,
                        projectPath,
                        gitRoot,
                        "git_staged_diff_failed",
                        stagedResult
                );
            }
            List<String> stagedFiles;
            try {
                stagedFiles = transactionResourceManager.readStagedFiles(
                        transactionResources,
                        maxPathspecBytes
                );
            } catch (GitTransactionResourceException exception) {
                return GenerationCommitResult.failed(
                        appId,
                        taskId,
                        projectPath.toString(),
                        headCommit(taskId, gitRoot),
                        currentBranch(taskId, gitRoot),
                        mapTransactionResourceFailure(exception)
                );
            }
            if (stagedFiles.isEmpty()) {
                return GenerationCommitResult.skipped(
                        appId,
                        taskId,
                        projectPath.toString(),
                        headCommit(taskId, gitRoot),
                        currentBranch(taskId, gitRoot),
                        "no_git_changes_after_stage"
                );
            }

            GitCommandResult commitResult = runGit(
                    taskId,
                    gitRoot,
                    List.of(
                            "-c",
                            "core.hooksPath=" + transactionResources.temporaryHooksDirectory(),
                            "-c",
                            "commit.gpgSign=false",
                            "commit",
                            "-m",
                            buildCommitMessage(appId, taskId)
                    ),
                    transactionEnvironment
            );
            if (!commitResult.success()) {
                return failedCommandResult(
                        appId,
                        taskId,
                        projectPath,
                        gitRoot,
                        "git_commit_failed",
                        commitResult
                );
            }
            GitCommandResult mainIndexSyncResult = runGit(
                    taskId,
                    gitRoot,
                    List.of(
                            "add",
                            "-A",
                            "--pathspec-from-file=" + transactionResources.temporaryPathspec(),
                            "--pathspec-file-nul"
                    )
            );
            if (!mainIndexSyncResult.success()) {
                return failedCommandResult(
                        appId,
                        taskId,
                        projectPath,
                        gitRoot,
                        "git_index_sync_failed_after_commit",
                        mainIndexSyncResult
                );
            }
            return GenerationCommitResult.committed(
                    appId,
                    taskId,
                    projectPath.toString(),
                    headCommit(taskId, gitRoot),
                    currentBranch(taskId, gitRoot),
                    stagedFiles
            );
        } finally {
            transactionResourceManager.cleanup(transactionResources);
        }
    }

    private GitCommandResult prepareTemporaryIndex(
            String taskId,
            Path gitRoot,
            Map<String, String> transactionEnvironment
    ) {
        GitCommandResult headResult = runGit(taskId, gitRoot, List.of("rev-parse", "--verify", "HEAD"));
        if (!headResult.commandCompleted()) {
            return headResult;
        }
        return headResult.success()
                ? runGit(taskId, gitRoot, List.of("read-tree", "HEAD"), transactionEnvironment)
                : runGit(taskId, gitRoot, List.of("read-tree", "--empty"), transactionEnvironment);
    }

    /** 构造表示命令执行失败的结果。 */
    private GenerationCommitResult failedCommandResult(
            Long appId,
            String taskId,
            Path projectPath,
            Path gitRoot,
            String reason,
            GitCommandResult commandResult
    ) {
        if (commandResult.interrupted() || Thread.currentThread().isInterrupted()) {
            return interruptedResult(appId, taskId, projectPath);
        }
        log.warn(
                "Git 提交命令失败，appId: {}, taskId: {}, reason: {}, status: {}, exitCode: {}",
                appId,
                taskId,
                reason,
                commandResult.status(),
                commandResult.exitCode()
        );
        return GenerationCommitResult.failed(
                appId,
                taskId,
                projectPath.toString(),
                headCommit(taskId, gitRoot),
                currentBranch(taskId, gitRoot),
                reason
        );
    }

    private GenerationCommitResult interruptedResult(
            Long appId,
            String taskId,
            Path projectPath
    ) {
        return GenerationCommitResult.failed(
                appId,
                taskId,
                projectPath.toString(),
                "",
                "",
                "git_commit_interrupted"
        );
    }

    /** 校验并返回有效的安全项目路径。 */
    private Path requireSafeProjectPath(Long appId, String currentPathValue) throws ProjectPathException {
        Path reportedPath;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            reportedPath = Path.of(currentPathValue).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw new ProjectPathException("", "project_path_invalid");
        }

        GenerationWorkspace workspace;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            workspace = generationWorkspaceService.resolveReportedWorkspace(appId, reportedPath);
        } catch (ReportedWorkspaceResolutionException exception) {
            throw new ProjectPathException("", mapReportedWorkspaceFailure(exception));
        } catch (RuntimeException exception) {
            throw new ProjectPathException("", "project_path_unavailable");
        }
        if (!workspace.exists()) {
            throw new ProjectPathException("", "project_path_missing");
        }
        try {
            if (!workspaceFileSystemService.isDirectory(workspace.canonicalRootPath())) {
                throw new ProjectPathException("", "project_path_missing");
            }
            return workspace.canonicalRootPath();
        } catch (WorkspaceFileSystemException exception) {
            String reason = exception.reason() == WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK
                    ? "project_path_unsafe"
                    : "project_path_unavailable";
            throw new ProjectPathException("", reason);
        } catch (IOException exception) {
            throw new ProjectPathException("", "project_path_unavailable");
        }
    }

    /** 返回变更文件。 */
    private ChangedFileSelection changedFiles(Map<String, Object> diffPayload) {
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (String payloadKey : List.of("addedFiles", "modifiedFiles", "deletedFiles")) {
            if (addNormalizedFiles(files, diffPayload.get(payloadKey))) {
                return new ChangedFileSelection(List.of(), true);
            }
        }
        return new ChangedFileSelection(List.copyOf(files), false);
    }

    /** 添加{@code Normalized}文件。 */
    private boolean addNormalizedFiles(LinkedHashSet<String> files, Object value) {
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (addNormalizedFile(files, item)) {
                    return true;
                }
            }
            return false;
        }
        return addNormalizedFile(files, value);
    }

    private boolean addNormalizedFile(LinkedHashSet<String> files, Object value) {
        String normalized = normalizeRelativeFile(value);
        if (StrUtil.isNotBlank(normalized)) {
            files.add(normalized);
        }
        return files.size() > maxFilesPerCommit;
    }

    /** 规范化{@code Relative}文件。 */
    private String normalizeRelativeFile(Object value) {
        if (value == null) {
            return "";
        }
        String candidate = String.valueOf(value).replace('\\', '/').trim();
        if (candidate.isBlank()
                || candidate.indexOf('\0') >= 0
                || candidate.indexOf('\r') >= 0
                || candidate.indexOf('\n') >= 0) {
            return "";
        }
        try {
            Path relativePath = Path.of(candidate).normalize();
            if (relativePath.isAbsolute()
                    || relativePath.getNameCount() == 0
                    || relativePath.startsWith("..")) {
                return "";
            }
            return relativePath.toString().replace('\\', '/');
        } catch (RuntimeException exception) {
            return "";
        }
    }

    /** 将当前对象转换为{@code Git}{@code Relative}文件。 */
    private List<String> toGitRelativeFiles(
            Path gitRoot,
            Path projectPath,
            List<String> projectRelativeFiles
    ) {
        List<String> result = new ArrayList<>();
        for (String relativeFile : projectRelativeFiles) {
            Path filePath = projectPath.resolve(relativeFile).toAbsolutePath().normalize();
            if (!filePath.startsWith(projectPath)) {
                continue;
            }
            Path gitRelativePath = gitRoot.relativize(filePath);
            if (gitRelativePath.getNameCount() > 0) {
                result.add(gitRelativePath.toString().replace('\\', '/'));
            }
        }
        return result.stream().distinct().toList();
    }

    private String buildCommitMessage(Long appId, String taskId) {
        String normalizedTaskId = StrUtil.blankToDefault(taskId, "unknown")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .strip();
        return "AI generation app " + appId + " task " + StrUtil.sub(normalizedTaskId, 0, 120);
    }

    private String headCommit(String taskId, Path gitRoot) {
        GitCommandResult result = runGit(taskId, gitRoot, List.of("rev-parse", "HEAD"));
        return result.success() ? result.stdout().trim() : "";
    }

    private String currentBranch(String taskId, Path gitRoot) {
        GitCommandResult result = runGit(taskId, gitRoot, List.of("rev-parse", "--abbrev-ref", "HEAD"));
        return result.success() ? result.stdout().trim() : "";
    }

    private GitCommandResult runGit(String taskId, Path workingDirectory, List<String> arguments) {
        return runGit(taskId, workingDirectory, arguments, Map.of());
    }

    private GitCommandResult runGit(
            String taskId,
            Path workingDirectory,
            List<String> arguments,
            Map<String, String> environment
    ) {
        return gitCommandExecutor.execute(
                workingDirectory,
                arguments,
                environment,
                "generation-commit",
                taskId
        );
    }

    private void acquireRepositoryLock(ReentrantLock repositoryLock, String taskId)
            throws InterruptedException {
        while (true) {
            executionContextService.assertCanContinue(taskId);
            if (repositoryLock.tryLock(
                    LOCK_POLICY_CHECK_INTERVAL.toMillis(),
                    TimeUnit.MILLISECONDS
            )) {
                try {
                    executionContextService.assertCanContinue(taskId);
                    return;
                } catch (RuntimeException | Error exception) {
                    repositoryLock.unlock();
                    throw exception;
                }
            }
        }
    }

    /** 根据当前上下文解析{@code Exact}目录。 */
    private Path resolveExactDirectory(String value, Path expectedDirectory) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            Path path = Path.of(value.trim()).toAbsolutePath().normalize();
            Path expected = expectedDirectory.toAbsolutePath().normalize();
            if (!path.equals(expected) || !workspaceFileSystemService.isDirectory(path)) {
                return null;
            }
            return path;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private String mapReportedWorkspaceFailure(ReportedWorkspaceResolutionException exception) {
        return switch (exception.reason()) {
            case CONTEXT_MISMATCH -> "project_path_context_mismatch";
            case UNSAFE_WORKSPACE -> "project_path_unsafe";
            case WORKSPACE_UNAVAILABLE -> "project_path_unavailable";
        };
    }

    private Map<String, Object> payload(GenerationArtifact artifact) {
        return artifact == null || artifact.payload() == null ? Map.of() : artifact.payload();
    }

    private boolean artifactContextMatches(Long appId, String taskId, Map<String, Object> diffPayload) {
        return Objects.equals(appId, longValue(diffPayload.get("appId")))
                && taskId.equals(stringValue(diffPayload.get("taskId")));
    }

    /** 返回{@code long}值。 */
    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String mapTransactionResourceFailure(GitTransactionResourceException exception) {
        return switch (exception.reason()) {
            case PATHSPEC_LIMIT_EXCEEDED -> "git_pathspec_limit_exceeded";
            case INVALID_PATHSPEC -> "git_pathspec_invalid";
            case INVALID_GIT_DIRECTORY -> "git_directory_unavailable";
            case STAGED_OUTPUT_INVALID -> "git_staged_output_invalid";
            case RESOURCE_CREATION_FAILED -> "git_transaction_resource_creation_failed";
        };
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private ReentrantLock lockFor(Path gitRoot) {
        return repositoryLocks[Math.floorMod(gitRoot.toString().hashCode(), repositoryLocks.length)];
    }

    /** 创建{@code Locks}。 */
    private ReentrantLock[] createLocks(int stripeCount) {
        if (stripeCount <= 0) {
            throw new IllegalArgumentException("Git 仓库提交锁条带数必须大于 0");
        }
        ReentrantLock[] locks = new ReentrantLock[stripeCount];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private int requirePositiveLimit(int value, String message) {
        if (value <= 0) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private record ChangedFileSelection(List<String> files, boolean limitExceeded) {

        private ChangedFileSelection {
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    private static final class ProjectPathException extends Exception {

        private final String reportedPath;
        private final String reason;

        private ProjectPathException(String reportedPath, String reason) {
            this.reportedPath = reportedPath;
            this.reason = reason;
        }

        private String reportedPath() {
            return reportedPath;
        }

        private String reason() {
            return reason;
        }
    }
}
