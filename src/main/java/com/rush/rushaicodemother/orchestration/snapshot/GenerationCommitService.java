package com.rush.rushaicodemother.orchestration.snapshot;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.config.GenerationCommitProperties;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.infrastructure.git.GitCommandExecutor;
import com.rush.rushaicodemother.infrastructure.git.GitCommandResult;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationCommitResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 生成成功后只提交本次生成工作区变更，输出本地 Git commit 元数据。
 */
@Slf4j
@Component
public class GenerationCommitService {

    private static final String TEMPORARY_INDEX_PREFIX = "ai-code-mother-";
    private static final String TEMPORARY_INDEX_SUFFIX = ".index";
    private static final String TEMPORARY_HOOKS_PREFIX = "ai-code-mother-hooks-";

    private final GenerationOrchestrationMetricsCollector metricsCollector;
    private final GitCommandExecutor gitCommandExecutor;
    private final Path codeOutputRoot;
    private final ReentrantLock[] repositoryLocks;

    @Autowired
    public GenerationCommitService(
            GenerationOrchestrationMetricsCollector metricsCollector,
            GitCommandExecutor gitCommandExecutor,
            GenerationCommitProperties properties
    ) {
        this(
                metricsCollector,
                gitCommandExecutor,
                Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR),
                properties
        );
    }

    GenerationCommitService(
            GenerationOrchestrationMetricsCollector metricsCollector,
            GitCommandExecutor gitCommandExecutor,
            Path codeOutputRoot
    ) {
        this(metricsCollector, gitCommandExecutor, codeOutputRoot, new GenerationCommitProperties());
    }

    GenerationCommitService(
            GenerationOrchestrationMetricsCollector metricsCollector,
            GitCommandExecutor gitCommandExecutor,
            Path codeOutputRoot,
            GenerationCommitProperties properties
    ) {
        this.metricsCollector = metricsCollector;
        this.gitCommandExecutor = gitCommandExecutor;
        this.codeOutputRoot = codeOutputRoot.toAbsolutePath().normalize();
        this.repositoryLocks = createLocks(properties.getLockStripes());
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
        Map<String, Object> diffPayload = payload(diffSummaryArtifact);
        String currentPathValue = stringValue(diffPayload.get("currentPath"));
        if (diffPayload.isEmpty()) {
            return GenerationCommitResult.skipped(appId, taskId, "", "", "", "diff_summary_missing");
        }
        if (!"created".equals(stringValue(diffPayload.get("status")))) {
            return GenerationCommitResult.skipped(
                    appId, taskId, currentPathValue, "", "", "diff_summary_not_created"
            );
        }
        List<String> changedFiles = changedFiles(diffPayload);
        if (changedFiles.isEmpty()) {
            return GenerationCommitResult.skipped(appId, taskId, currentPathValue, "", "", "no_diff_files");
        }
        if (StrUtil.isBlank(currentPathValue)) {
            return GenerationCommitResult.skipped(appId, taskId, "", "", "", "current_path_missing");
        }

        Path projectPath;
        try {
            projectPath = requireSafeProjectPath(currentPathValue);
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
            return commitChangedFiles(appId, taskId, projectPath, changedFiles);
        } catch (Exception exception) {
            log.warn("生成结果本地 Git 提交失败，appId: {}, taskId: {}", appId, taskId, exception);
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
                        "git_root_lookup_failed:" + gitRootResult.errorSummary()
                );
            }
            return GenerationCommitResult.skipped(
                    appId, taskId, projectPath.toString(), "", "", "git_repository_missing"
            );
        }
        Path gitRoot = resolveGitDirectory(gitRootResult.stdout());
        if (gitRoot == null || !projectPath.startsWith(gitRoot)) {
            return GenerationCommitResult.skipped(
                    appId, taskId, projectPath.toString(), "", "", "project_not_inside_git_root"
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
        Path gitDirectory = gitDirectoryResult.success()
                ? resolveGitDirectory(gitDirectoryResult.stdout())
                : null;
        if (gitDirectory == null) {
            if (!gitDirectoryResult.success()) {
                return failedCommandResult(
                        appId,
                        taskId,
                        projectPath,
                        gitRoot,
                        "git_directory_unavailable:",
                        gitDirectoryResult
                );
            }
            return GenerationCommitResult.failed(
                    appId,
                    taskId,
                    projectPath.toString(),
                    headCommit(gitRoot),
                    currentBranch(gitRoot),
                    "git_directory_unavailable:invalid_git_directory"
            );
        }

        String transactionId = UUID.randomUUID().toString();
        Path temporaryIndex = gitDirectory.resolve(
                TEMPORARY_INDEX_PREFIX + transactionId + TEMPORARY_INDEX_SUFFIX
        ).normalize();
        Path temporaryHooksDirectory = gitDirectory.resolve(
                TEMPORARY_HOOKS_PREFIX + transactionId
        ).normalize();
        if (!temporaryIndex.startsWith(gitDirectory)
                || !temporaryHooksDirectory.startsWith(gitDirectory)) {
            throw new IOException("Git 临时资源路径越界");
        }

        Files.createDirectory(temporaryHooksDirectory);
        Map<String, String> transactionEnvironment = Map.of(
                "GIT_INDEX_FILE", temporaryIndex.toString()
        );
        try {
            GitCommandResult indexResult = prepareTemporaryIndex(gitRoot, transactionEnvironment);
            if (!indexResult.success()) {
                return failedCommandResult(
                        appId,
                        taskId,
                        projectPath,
                        gitRoot,
                        "git_index_prepare_failed:",
                        indexResult
                );
            }

            GitCommandResult stageResult = runGit(
                    gitRoot,
                    withPathspec(List.of("add", "-A", "--"), gitRelativeFiles),
                    transactionEnvironment
            );
            if (!stageResult.success()) {
                return failedCommandResult(
                        appId,
                        taskId,
                        projectPath,
                        gitRoot,
                        "git_stage_failed:",
                        stageResult
                );
            }

            GitCommandResult stagedResult = runGit(
                    gitRoot,
                    withPathspec(List.of("diff", "--cached", "--name-only", "--"), gitRelativeFiles),
                    transactionEnvironment
            );
            if (!stagedResult.success()) {
                return failedCommandResult(
                        appId,
                        taskId,
                        projectPath,
                        gitRoot,
                        "git_staged_diff_failed:",
                        stagedResult
                );
            }
            List<String> stagedFiles = nonBlankLines(stagedResult.stdout());
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
                            "core.hooksPath=" + temporaryHooksDirectory,
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
                        "git_commit_failed:",
                        commitResult
                );
            }
            GitCommandResult mainIndexSyncResult = runGit(
                    gitRoot,
                    withPathspec(List.of("add", "-A", "--"), gitRelativeFiles)
            );
            if (!mainIndexSyncResult.success()) {
                return failedCommandResult(
                        appId,
                        taskId,
                        projectPath,
                        gitRoot,
                        "git_index_sync_failed_after_commit:",
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
            deleteTemporaryGitResource(temporaryIndex.resolveSibling(
                    temporaryIndex.getFileName() + ".lock"
            ));
            deleteTemporaryGitResource(temporaryIndex);
            deleteTemporaryGitResource(temporaryHooksDirectory);
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
            String reasonPrefix,
            GitCommandResult commandResult
    ) {
        if (commandResult.interrupted() || Thread.currentThread().isInterrupted()) {
            return interruptedResult(appId, taskId, projectPath);
        }
        return GenerationCommitResult.failed(
                appId,
                taskId,
                projectPath.toString(),
                headCommit(gitRoot),
                currentBranch(gitRoot),
                reasonPrefix + commandResult.errorSummary()
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

    private Path requireSafeProjectPath(String currentPathValue) throws ProjectPathException {
        Path reportedPath;
        try {
            reportedPath = Path.of(currentPathValue).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw new ProjectPathException("", "project_path_invalid");
        }
        if (!Files.isDirectory(reportedPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new ProjectPathException(reportedPath.toString(), "project_path_missing");
        }
        if (Files.isSymbolicLink(reportedPath)) {
            throw new ProjectPathException(reportedPath.toString(), "project_path_unsafe");
        }
        try {
            if (Files.isSymbolicLink(codeOutputRoot)
                    || !Files.isDirectory(codeOutputRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new ProjectPathException(reportedPath.toString(), "project_path_out_of_root");
            }
            Path realRoot = codeOutputRoot.toRealPath();
            Path realProject = reportedPath.toRealPath();
            if (!realProject.startsWith(realRoot)) {
                throw new ProjectPathException(realProject.toString(), "project_path_out_of_root");
            }
            return realProject;
        } catch (IOException | SecurityException exception) {
            throw new ProjectPathException(reportedPath.toString(), "project_path_unavailable");
        }
    }

    private List<String> changedFiles(Map<String, Object> diffPayload) {
        LinkedHashSet<String> files = new LinkedHashSet<>();
        files.addAll(normalizeFiles(diffPayload.get("addedFiles")));
        files.addAll(normalizeFiles(diffPayload.get("modifiedFiles")));
        files.addAll(normalizeFiles(diffPayload.get("deletedFiles")));
        return List.copyOf(files);
    }

    private List<String> normalizeFiles(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(this::normalizeRelativeFile)
                    .filter(StrUtil::isNotBlank)
                    .distinct()
                    .toList();
        }
        String normalized = normalizeRelativeFile(value);
        return StrUtil.isBlank(normalized) ? List.of() : List.of(normalized);
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

    private List<String> withPathspec(List<String> command, List<String> pathspecs) {
        List<String> result = new ArrayList<>(command);
        result.addAll(pathspecs);
        return result;
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
                "generation-commit " + workingDirectory
        );
    }

    private Path resolveGitDirectory(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            Path path = Path.of(value.trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                return null;
            }
            return path.toRealPath();
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private List<String> nonBlankLines(String value) {
        if (StrUtil.isBlank(value)) {
            return List.of();
        }
        return value.lines()
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
    }

    private void deleteTemporaryGitResource(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("清理 Git 临时资源失败: {}", path, exception);
        }
    }

    private Map<String, Object> payload(GenerationArtifact artifact) {
        return artifact == null || artifact.payload() == null ? Map.of() : artifact.payload();
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
