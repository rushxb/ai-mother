package com.rush.rushaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 手动快照的不可变数据载体。
 */
public record ManualSnapshot(
        String key,
        String provider,
        String status,
        String snapshotName,
        String snapshotId,
        String manifestSha256,
        String codeGenType,
        String scope,
        long executionEpoch,
        Long appId,
        String taskId,
        String projectPath,
        String snapshotPath,
        String source,
        long fileCount,
        LocalDateTime createdAt
) {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public ManualSnapshot {
        key = StrUtil.blankToDefault(key, "manual_snapshot");
        provider = StrUtil.blankToDefault(provider, "SnapshotRollbackTool");
        status = StrUtil.blankToDefault(status, "created");
        source = StrUtil.blankToDefault(source, "ai_tool");
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
        snapshotName = requireText(snapshotName, "snapshotName");
        snapshotId = requireCanonicalUuid(snapshotId);
        manifestSha256 = requireText(manifestSha256, "manifestSha256").toLowerCase();
        if (!SHA256_HEX.matcher(manifestSha256).matches()) {
            throw new IllegalArgumentException("manifestSha256 must be lowercase SHA-256");
        }
        codeGenType = requireText(codeGenType, "codeGenType");
        scope = requireText(scope, "scope");
        taskId = requireText(taskId, "taskId");
        projectPath = requireText(projectPath, "projectPath");
        snapshotPath = requireText(snapshotPath, "snapshotPath");
        if (appId == null || appId <= 0 || executionEpoch <= 0 || fileCount < 0) {
            throw new IllegalArgumentException("manual snapshot numeric identity is invalid");
        }
    }

    /**
 * 将当前对象转换为载荷。
 *
 * @return 载荷集合
 */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", key);
        payload.put("provider", provider);
        payload.put("status", status);
        payload.put("snapshotName", snapshotName);
        payload.put("snapshotId", snapshotId);
        payload.put("manifestSha256", manifestSha256);
        payload.put("codeGenType", codeGenType);
        payload.put("scope", scope);
        payload.put("executionEpoch", executionEpoch);
        payload.put("appId", appId);
        payload.put("taskId", taskId);
        payload.put("projectPath", projectPath);
        payload.put("snapshotPath", snapshotPath);
        payload.put("source", source);
        payload.put("fileCount", fileCount);
        payload.put("createdAt", createdAt.toString());
        return payload;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String requireCanonicalUuid(String value) {
        String normalized = requireText(value, "snapshotId");
        if (!UUID.fromString(normalized).toString().equals(normalized)) {
            throw new IllegalArgumentException("snapshotId must be a canonical UUID");
        }
        return normalized;
    }
}
