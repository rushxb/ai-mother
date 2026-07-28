package com.rush.rushaicodemother.orchestration.artifact;

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

    /** 创建生成提交结果实例并完成必要的依赖和初始状态设置。 */
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

    /**
 * 返回{@code committed}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param projectPath 项目路径
 * @param commitId 提交编号
 * @param branch {@code branch} 对应的调用参数
 * @param committedFiles 待处理的 {@code committedFiles} 集合
 * @return 生成提交结果
 */
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

    /**
 * 返回{@code skipped}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param projectPath 项目路径
 * @param commitId 提交编号
 * @param branch {@code branch} 对应的调用参数
 * @param reason 原因
 * @return 生成提交结果
 */
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

    /**
 * 将{@code ed}标记为失败并记录原因。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param projectPath 项目路径
 * @param commitId 提交编号
 * @param branch {@code branch} 对应的调用参数
 * @param reason 原因
 * @return {@code ed}
 */
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

    /**
 * 将当前对象转换为载荷。
 *
 * @return 载荷集合
 */
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
