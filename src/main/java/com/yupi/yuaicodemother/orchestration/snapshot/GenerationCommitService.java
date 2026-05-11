package com.yupi.yuaicodemother.orchestration.snapshot;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationCommitResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 生成成功后只提交本次生成工作区变更，输出本地 Git commit 元数据。
 */
@Slf4j
@Component
public class GenerationCommitService {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(10);

    private final GenerationOrchestrationMetricsCollector metricsCollector;
    private final Path codeOutputRoot;

    @Autowired
    public GenerationCommitService(GenerationOrchestrationMetricsCollector metricsCollector) {
        this(metricsCollector, Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR));
    }

    public GenerationCommitService(GenerationOrchestrationMetricsCollector metricsCollector, Path codeOutputRoot) {
        this.metricsCollector = metricsCollector;
        this.codeOutputRoot = codeOutputRoot.toAbsolutePath().normalize();
    }

    public GenerationArtifact commitIfAllowed(Long appId,
                                              String taskId,
                                              GenerationArtifact diffSummaryArtifact) {
        GenerationCommitResult result = commit(appId, taskId, diffSummaryArtifact);
        metricsCollector.recordGenerationCommit(result.provider(), result.status(), result.reason());
        return GenerationArtifact.of("generation_commit", "Orchestrator", "生成结果本地 Git 提交", result.toPayload());
    }

    public GenerationCommitResult commit(Long appId,
                                         String taskId,
                                         GenerationArtifact diffSummaryArtifact) {
        Map<String, Object> diffPayload = payload(diffSummaryArtifact);
        String currentPathValue = stringValue(diffPayload.get("currentPath"));
        if (diffPayload.isEmpty()) {
            return GenerationCommitResult.skipped(appId, taskId, "", "", "", "diff_summary_missing");
        }
        if (!"created".equals(stringValue(diffPayload.get("status")))) {
            return GenerationCommitResult.skipped(appId, taskId, currentPathValue, "", "", "diff_summary_not_created");
        }
        List<String> changedFiles = changedFiles(diffPayload);
        if (changedFiles.isEmpty()) {
            return GenerationCommitResult.skipped(appId, taskId, currentPathValue, "", "", "no_diff_files");
        }
        if (StrUtil.isBlank(currentPathValue)) {
            return GenerationCommitResult.skipped(appId, taskId, "", "", "", "current_path_missing");
        }
        Path projectPath = Path.of(currentPathValue).toAbsolutePath().normalize();
        if (!projectPath.startsWith(codeOutputRoot.toAbsolutePath().normalize())) {
            return GenerationCommitResult.skipped(appId, taskId, projectPath.toString(), "", "", "project_path_out_of_root");
        }
        if (!Files.isDirectory(projectPath)) {
            return GenerationCommitResult.skipped(appId, taskId, projectPath.toString(), "", "", "project_path_missing");
        }

        try {
            GitCommandResult gitRootResult = runGit(projectPath, List.of("rev-parse", "--show-toplevel"));
            if (!gitRootResult.success()) {
                return GenerationCommitResult.skipped(appId, taskId, projectPath.toString(), "", "", "git_repository_missing");
            }
            Path gitRoot = Path.of(gitRootResult.stdout().trim()).toAbsolutePath().normalize();
            if (!projectPath.startsWith(gitRoot)) {
                return GenerationCommitResult.skipped(appId, taskId, projectPath.toString(), "", "", "project_not_inside_git_root");
            }
            List<String> gitRelativeFiles = toGitRelativeFiles(gitRoot, projectPath, changedFiles);
            if (gitRelativeFiles.isEmpty()) {
                return GenerationCommitResult.skipped(appId, taskId, projectPath.toString(), "", currentBranch(gitRoot), "no_committable_files");
            }
            GitCommandResult stageResult = runGit(gitRoot, withPathspec(List.of("add", "-A", "--"), gitRelativeFiles));
            if (!stageResult.success()) {
                return GenerationCommitResult.failed(
                        appId,
                        taskId,
                        projectPath.toString(),
                        headCommit(gitRoot),
                        currentBranch(gitRoot),
                        "git_stage_failed:" + stageResult.stderrSummary()
                );
            }
            GitCommandResult stagedResult = runGit(gitRoot, withPathspec(List.of("diff", "--cached", "--name-only", "--"), gitRelativeFiles));
            List<String> stagedFiles = stagedResult.success() ? nonBlankLines(stagedResult.stdout()) : List.of();
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
            GitCommandResult commitResult = runGit(gitRoot, withPathspec(
                    List.of("commit", "-m", buildCommitMessage(appId, taskId), "--"),
                    stagedFiles
            ));
            if (!commitResult.success()) {
                return GenerationCommitResult.failed(
                        appId,
                        taskId,
                        projectPath.toString(),
                        headCommit(gitRoot),
                        currentBranch(gitRoot),
                        "git_commit_failed:" + commitResult.stderrSummary()
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
        } catch (Exception e) {
            log.warn("生成结果本地 Git 提交失败，appId: {}, taskId: {}", appId, taskId, e);
            return GenerationCommitResult.failed(appId, taskId, projectPath.toString(), "", "", "git_commit_exception:" + e.getMessage());
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
                    .filter(item -> item != null && StrUtil.isNotBlank(String.valueOf(item)))
                    .map(item -> String.valueOf(item).replace("\\", "/").trim())
                    .filter(path -> !path.startsWith("/") && !path.contains(".."))
                    .distinct()
                    .toList();
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            String path = text.replace("\\", "/").trim();
            if (!path.startsWith("/") && !path.contains("..")) {
                return List.of(path);
            }
        }
        return List.of();
    }

    private List<String> toGitRelativeFiles(Path gitRoot, Path projectPath, List<String> projectRelativeFiles) {
        List<String> result = new ArrayList<>();
        for (String relativeFile : projectRelativeFiles) {
            Path filePath = projectPath.resolve(relativeFile).toAbsolutePath().normalize();
            if (!filePath.startsWith(projectPath)) {
                continue;
            }
            result.add(gitRoot.relativize(filePath).toString().replace("\\", "/"));
        }
        return result.stream().distinct().toList();
    }

    private List<String> withPathspec(List<String> command, List<String> pathspecs) {
        List<String> result = new ArrayList<>(command);
        result.addAll(pathspecs);
        return result;
    }

    private String buildCommitMessage(Long appId, String taskId) {
        return "AI generation app " + appId + " task " + StrUtil.blankToDefault(taskId, "unknown");
    }

    private String headCommit(Path gitRoot) {
        GitCommandResult result = runGit(gitRoot, List.of("rev-parse", "HEAD"));
        return result.success() ? result.stdout().trim() : "";
    }

    private String currentBranch(Path gitRoot) {
        GitCommandResult result = runGit(gitRoot, List.of("rev-parse", "--abbrev-ref", "HEAD"));
        return result.success() ? result.stdout().trim() : "";
    }

    private GitCommandResult runGit(Path workingDir, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(arguments);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDir.toFile());
        processBuilder.environment().putIfAbsent("GIT_AUTHOR_NAME", "ai-code-mother");
        processBuilder.environment().putIfAbsent("GIT_AUTHOR_EMAIL", "ai-code-mother@example.com");
        processBuilder.environment().putIfAbsent("GIT_COMMITTER_NAME", "ai-code-mother");
        processBuilder.environment().putIfAbsent("GIT_COMMITTER_EMAIL", "ai-code-mother@example.com");
        try {
            Process process = processBuilder.start();
            boolean finished = process.waitFor(GIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!finished) {
                process.destroyForcibly();
                return new GitCommandResult(-1, stdout, stderr + "\ngit_timeout");
            }
            return new GitCommandResult(process.exitValue(), stdout, stderr);
        } catch (IOException e) {
            return new GitCommandResult(-1, "", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GitCommandResult(-1, "", e.getMessage());
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

    private Map<String, Object> payload(GenerationArtifact artifact) {
        return artifact == null || artifact.payload() == null ? Map.of() : artifact.payload();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record GitCommandResult(int exitCode, String stdout, String stderr) {

        private boolean success() {
            return exitCode == 0;
        }

        private String stderrSummary() {
            if (StrUtil.isBlank(stderr)) {
                return "unknown";
            }
            String cleaned = stderr.replace("\r", " ").replace("\n", " ").trim();
            return StrUtil.sub(cleaned, 0, Math.min(cleaned.length(), 160));
        }
    }
}
