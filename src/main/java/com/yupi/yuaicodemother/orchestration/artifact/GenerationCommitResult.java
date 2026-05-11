package com.yupi.yuaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成结果本地 Git 提交契约。
 */
public record GenerationCommitResult(
        String schemaVersion,
        String provider,
        String status,
        Long appId,
        String taskId,
        String projectPath,
        String commitId,
        String shortCommitId,
        String branch,
        int committedFileCount,
        List<String> committedFiles,
        String reason,
        LocalDateTime createdAt
) {

    public GenerationCommitResult {
        schemaVersion = StrUtil.blankToDefault(schemaVersion, "v1");
        provider = StrUtil.blankToDefault(provider, "local_git");
        status = StrUtil.blankToDefault(status, "skipped");
        projectPath = StrUtil.blankToDefault(projectPath, "");
        commitId = StrUtil.blankToDefault(commitId, "");
        shortCommitId = StrUtil.blankToDefault(shortCommitId, "");
        branch = StrUtil.blankToDefault(branch, "");
        committedFiles = committedFiles == null ? List.of() : List.copyOf(committedFiles);
        reason = StrUtil.blankToDefault(reason, "");
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    public static GenerationCommitResult committed(Long appId,
                                                   String taskId,
                                                   String projectPath,
                                                   String commitId,
                                                   String branch,
                                                   List<String> committedFiles) {
        String shortCommitId = StrUtil.isBlank(commitId) ? "" : StrUtil.sub(commitId, 0, Math.min(12, commitId.length()));
        return new GenerationCommitResult(
                "v1",
                "local_git",
                "committed",
                appId,
                taskId,
                projectPath,
                commitId,
                shortCommitId,
                branch,
                committedFiles == null ? 0 : committedFiles.size(),
                committedFiles,
                "",
                LocalDateTime.now()
        );
    }

    public static GenerationCommitResult skipped(Long appId,
                                                 String taskId,
                                                 String projectPath,
                                                 String commitId,
                                                 String branch,
                                                 String reason) {
        String shortCommitId = StrUtil.isBlank(commitId) ? "" : StrUtil.sub(commitId, 0, Math.min(12, commitId.length()));
        return new GenerationCommitResult(
                "v1",
                "local_git",
                "skipped",
                appId,
                taskId,
                projectPath,
                commitId,
                shortCommitId,
                branch,
                0,
                List.of(),
                reason,
                LocalDateTime.now()
        );
    }

    public static GenerationCommitResult failed(Long appId,
                                                String taskId,
                                                String projectPath,
                                                String commitId,
                                                String branch,
                                                String reason) {
        String shortCommitId = StrUtil.isBlank(commitId) ? "" : StrUtil.sub(commitId, 0, Math.min(12, commitId.length()));
        return new GenerationCommitResult(
                "v1",
                "local_git",
                "failed",
                appId,
                taskId,
                projectPath,
                commitId,
                shortCommitId,
                branch,
                0,
                List.of(),
                reason,
                LocalDateTime.now()
        );
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", schemaVersion);
        payload.put("provider", provider);
        payload.put("status", status);
        payload.put("appId", appId);
        payload.put("taskId", taskId);
        payload.put("projectPath", projectPath);
        payload.put("commitId", commitId);
        payload.put("shortCommitId", shortCommitId);
        payload.put("branch", branch);
        payload.put("committedFileCount", committedFileCount);
        payload.put("committedFiles", committedFiles);
        payload.put("reason", reason);
        payload.put("createdAt", createdAt.toString());
        return payload;
    }
}
