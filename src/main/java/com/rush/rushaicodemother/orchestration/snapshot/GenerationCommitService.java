package com.rush.rushaicodemother.orchestration.snapshot;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.config.GenerationCommitProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemException;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.git.GitCommandExecutor;
import com.rush.rushaicodemother.infrastructure.git.GitCommandResult;
import com.rush.rushaicodemother.infrastructure.git.GitTransactionResourceManager;
import com.rush.rushaicodemother.infrastructure.git.GitTransactionResourceManager.GitTransactionResourceException;
import com.rush.rushaicodemother.infrastructure.git.GitTransactionResourceManager.GitTransactionResources;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationCommitResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 生成成功后只提交本次生成工作区变更，输出本地 Git commit 元数据。
 */
@Slf4j
@Component
public class GenerationCommitService {

    private final GenerationOrchestrationMetricsCollector metricsCollector;
    private final GitCommandExecutor gitCommandExecutor;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final GitTransactionResourceManager transactionResourceManager;
    private final Path codeOutputRoot;
    private final ReentrantLock[] repositoryLocks;
    private final int maxFilesPerCommit;
    private final int maxPathspecBytes;

    @Autowired
    public GenerationCommitService(
            GenerationOrchestrationMetricsCollector metricsCollector,
            GitCommandExecutor gitCommandExecutor,
            WorkspaceFileSystemService workspaceFileSystemService,
            GitTransactionResourceManager transactionResourceManager,
            GenerationCommitProperties properties
    ) {
        this(
                metricsCollector,
                gitCommandExecutor,
                workspaceFileSystemService,
                transactionResourceManager,
                Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR),
                properties
        );
    }

    GenerationCommitService(
            GenerationOrchestrationMetricsCollector metricsCollector,
            GitCommandExecutor gitCommandExecutor,
            Path codeOutputRoot
    ) {
        this(
                metricsCollector,
                gitCommandExecutor,
                new WorkspaceFileSystemService(new WorkspaceFileSystemProperties()),
                new GitTransactionResourceManager(),
                codeOutputRoot,
                new GenerationCommitProperties()
        );
    }

    GenerationCommitService(
            GenerationOrchestrationMetricsCollector metricsCollector,
            GitCommandExecutor gitCommandExecutor,
            Path codeOutputRoot,
            GenerationCommitProperties properties
    ) {
        this(
                metricsCollector,
                gitCommandExecutor,
                new WorkspaceFileSystemService(new WorkspaceFileSystemProperties()),
                new GitTransactionResourceManager(),
                codeOutputRoot,
                properties
        );
    }

    GenerationCommitService(
            GenerationOrchestrationMetricsCollector metricsCollector,
            GitCommandExecutor gitCommandExecutor,
            WorkspaceFileSystemService workspaceFileSystemService,
            GitTransactionResourceManager transactionResourceManager,
            Path codeOutputRoot,
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
        this.codeOutputRoot = Objects.requireNonNull(codeOutputRoot, "codeOutputRoot must not be null")
                .toAbsolutePath()
                .normalize();
        Objects.requireNonNull(properties, "properties must not be null");
        this.repositoryLocks = createLocks(properties.getLockStripes());
        this.maxFilesPerCommit = requirePositiveLimit(
                properties.getMaxFilesPerCommit(),
                "Git 单次提交文件数上限必须大于 0"
        );
        this.maxPathspecBytes = requirePositiveLimit(
                properties.getMaxPathspecBytes(),
                "Git pathspec 字节上限必须大于 0"
        );
    }

    public GenerationArtifact commitIfAllowed(
            Long appId,
            String taskId,
            GenerationArtifact diffSummaryArtifact
    ) {
        GenerationCommitResult result = commit(appId, taskId, diffSummaryArtifact);
        metricsCollector.recordGenerationCommit(result.provider(), result.status(), result.reason());
        return GenerationArtifact.of("generation_commit", "Orchestrator", "生成结果本地 Git 提交", result.toPayload());
    }

    public GenerationCommitResult commit(
            Long appId,
            String taskId,
            GenerationArtifact diffSummaryArtifact
    ) {
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

    private GenerationCommitResult commitChangedFiles(
            Long appId,
            String taskId,
            Path projectPath,
            List<String> changedFiles
    ) throws IOException {
        GitCommandResult gitRootResult = runGit(projectPath, List.of("rev-parse", "--show-toplevel"));
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
                    currentBranch(gitRoot),
                    "no_committable_files"
            );
        }

        ReentrantLock repositoryLock = lockFor(gitRoot);
        try {
            repositoryLock.lockInterruptibly();
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

    private GenerationCommitResult commitWithRepositoryLock(
            Long appId,
            String taskId,
            Path projectPath,
            Path gitRoot,
            List<String> gitRelativeFiles
    ) throws IOException {
        GitCommandResult gitDirectoryResult = runGit(
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
                    headCommit(gitRoot),
                    currentBranch(gitRoot),
                    "git_directory_unavailable"
            );
        }

        GitTransactionResources transactionResources;
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
                    headCommit(gitRoot),
                    currentBranch(gitRoot),
                    mapTransactionResourceFailure(exception)
            );
        }
        Map<String, String> transactionEnvironment = Map.of(
                "GIT_INDEX_FILE", transactionResources.temporaryIndex().toString()
        );
        try {
            GitCommandResult indexResult = prepareTemporaryIndex(gitRoot, transactionEnvironment);
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
                        headCommit(gitRoot),
                        currentBranch(gitRoot),
                        mapTransactionResourceFailure(exception)
                );
            }
            if (stagedFiles.isEmpty()) {
                return GenerationCommitResult.skipped(
                        appId,
                        taskId,
                        projectPath.toString(),
                        headCommit(gitRoot),
                        currentBranch(gitRoot),
                        "no_git_changes_after_stage"
                );
            }

            GitCommandResult commitResult = runGit(
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
                    headCommit(gitRoot),
                    currentBranch(gitRoot),
                    stagedFiles
            );
        } finally {
            transactionResourceManager.cleanup(transactionResources);
        }
    }

    private GitCommandResult prepareTemporaryIndex(
            Path gitRoot,
            Map<String, String> transactionEnvironment
    ) {
        GitCommandResult headResult = runGit(gitRoot, List.of("rev-parse", "--verify", "HEAD"));
        if (!headResult.commandCompleted()) {
            return headResult;
        }
        return headResult.success()
                ? runGit(gitRoot, List.of("read-tree", "HEAD"), transactionEnvironment)
                : runGit(gitRoot, List.of("read-tree", "--empty"), transactionEnvironment);
    }

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
                headCommit(gitRoot),
                currentBranch(gitRoot),
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

    private Path requireSafeProjectPath(Long appId, String currentPathValue) throws ProjectPathException {
        Path reportedPath;
        try {
            reportedPath = Path.of(currentPathValue).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw new ProjectPathException("", "project_path_invalid");
        }
        if (!isExpectedProjectDirectoryName(appId, reportedPath)) {
            throw new ProjectPathException("", "project_path_context_mismatch");
        }
        try {
            return workspaceFileSystemService.resolveExistingDirectChildDirectory(codeOutputRoot, reportedPath);
        } catch (WorkspaceFileSystemException exception) {
            String reason = switch (exception.reason()) {
                case MISSING_DIRECTORY -> "project_path_missing";
                case UNSAFE_SYMBOLIC_LINK -> "project_path_unsafe";
                case INVALID_PATH -> "project_path_out_of_root";
                default -> "project_path_unavailable";
            };
            throw new ProjectPathException("", reason);
        } catch (IOException | RuntimeException exception) {
            throw new ProjectPathException("", "project_path_unavailable");
        }
    }

    private boolean isExpectedProjectDirectoryName(Long appId, Path reportedPath) {
        if (reportedPath.getFileName() == null) {
            return false;
        }
        String directoryName = reportedPath.getFileName().toString();
        for (CodeGenTypeEnum codeGenType : CodeGenTypeEnum.values()) {
            if ((codeGenType.getValue() + "_" + appId).equals(directoryName)) {
                return true;
            }
        }
        return false;
    }

    private ChangedFileSelection changedFiles(Map<String, Object> diffPayload) {
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (String payloadKey : List.of("addedFiles", "modifiedFiles", "deletedFiles")) {
            if (addNormalizedFiles(files, diffPayload.get(payloadKey))) {
                return new ChangedFileSelection(List.of(), true);
            }
        }
        return new ChangedFileSelection(List.copyOf(files), false);
    }

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

    private String headCommit(Path gitRoot) {
        GitCommandResult result = runGit(gitRoot, List.of("rev-parse", "HEAD"));
        return result.success() ? result.stdout().trim() : "";
    }

    private String currentBranch(Path gitRoot) {
        GitCommandResult result = runGit(gitRoot, List.of("rev-parse", "--abbrev-ref", "HEAD"));
        return result.success() ? result.stdout().trim() : "";
    }

    private GitCommandResult runGit(Path workingDirectory, List<String> arguments) {
        return runGit(workingDirectory, arguments, Map.of());
    }

    private GitCommandResult runGit(
            Path workingDirectory,
            List<String> arguments,
            Map<String, String> environment
    ) {
        return gitCommandExecutor.execute(
                workingDirectory,
                arguments,
                environment,
                "generation-commit"
        );
    }

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

    private Map<String, Object> payload(GenerationArtifact artifact) {
        return artifact == null || artifact.payload() == null ? Map.of() : artifact.payload();
    }

    private boolean artifactContextMatches(Long appId, String taskId, Map<String, Object> diffPayload) {
        return Objects.equals(appId, longValue(diffPayload.get("appId")))
                && taskId.equals(stringValue(diffPayload.get("taskId")));
    }

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
