package com.rush.rushaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成后差异摘要契约。
 */
public record DiffSummary(
        String schemaVersion,
        String provider,
        String status,
        Long appId,
        String taskId,
        String basePath,
        String currentPath,
        int addedCount,
        int modifiedCount,
        int deletedCount,
        List<String> addedFiles,
        List<String> modifiedFiles,
        List<String> deletedFiles,
        List<String> modifiedDetails,
        String reason,
        LocalDateTime createdAt
) {

    /** 创建{@code Diff}汇总实例并完成必要的依赖和初始状态设置。 */
    public DiffSummary {
        schemaVersion = StrUtil.blankToDefault(schemaVersion, "v1");
        provider = StrUtil.blankToDefault(provider, "local_snapshot");
        status = StrUtil.blankToDefault(status, "skipped");
        addedFiles = addedFiles == null ? List.of() : List.copyOf(addedFiles);
        modifiedFiles = modifiedFiles == null ? List.of() : List.copyOf(modifiedFiles);
        deletedFiles = deletedFiles == null ? List.of() : List.copyOf(deletedFiles);
        modifiedDetails = modifiedDetails == null ? List.of() : List.copyOf(modifiedDetails);
        reason = StrUtil.blankToDefault(reason, "");
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    /**
 * 创建{@code d}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param basePath 基础路径
 * @param currentPath 当前路径
 * @param addedFiles 待处理的 {@code addedFiles} 集合
 * @param modifiedFiles 待处理的 {@code modifiedFiles} 集合
 * @param deletedFiles 待处理的 {@code deletedFiles} 集合
 * @param modifiedDetails 待处理的 {@code modifiedDetails} 集合
 * @return {@code d}
 */
    public static DiffSummary created(Long appId,
                                      String taskId,
                                      String basePath,
                                      String currentPath,
                                      List<String> addedFiles,
                                      List<String> modifiedFiles,
                                      List<String> deletedFiles,
                                      List<String> modifiedDetails) {
        return new DiffSummary(
                "v1",
                "local_snapshot",
                "created",
                appId,
                taskId,
                basePath,
                currentPath,
                addedFiles == null ? 0 : addedFiles.size(),
                modifiedFiles == null ? 0 : modifiedFiles.size(),
                deletedFiles == null ? 0 : deletedFiles.size(),
                addedFiles,
                modifiedFiles,
                deletedFiles,
                modifiedDetails,
                "",
                LocalDateTime.now()
        );
    }

    /**
 * 返回{@code skipped}。
 *
 * @param appId 应用编号
 * @param taskId 任务编号
 * @param basePath 基础路径
 * @param currentPath 当前路径
 * @param reason 原因
 * @return {@code Diff}汇总
 */
    public static DiffSummary skipped(Long appId,
                                      String taskId,
                                      String basePath,
                                      String currentPath,
                                      String reason) {
        return new DiffSummary(
                "v1",
                "local_snapshot",
                "skipped",
                appId,
                taskId,
                basePath,
                currentPath,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
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
        payload.put("basePath", basePath);
        payload.put("currentPath", currentPath);
        payload.put("addedCount", addedCount);
        payload.put("modifiedCount", modifiedCount);
        payload.put("deletedCount", deletedCount);
        payload.put("addedFiles", addedFiles);
        payload.put("modifiedFiles", modifiedFiles);
        payload.put("deletedFiles", deletedFiles);
        payload.put("modifiedDetails", modifiedDetails);
        payload.put("reason", reason);
        payload.put("createdAt", createdAt.toString());
        return payload;
    }
}
