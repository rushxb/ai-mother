package com.rush.rushaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public record ManualSnapshot(
        String key,
        String provider,
        String status,
        String snapshotName,
        Long appId,
        String taskId,
        String projectPath,
        String snapshotPath,
        String source,
        long fileCount,
        LocalDateTime createdAt
) {

    public ManualSnapshot {
        key = StrUtil.blankToDefault(key, "manual_snapshot");
        provider = StrUtil.blankToDefault(provider, "SnapshotRollbackTool");
        status = StrUtil.blankToDefault(status, "created");
        source = StrUtil.blankToDefault(source, "ai_tool");
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", key);
        payload.put("provider", provider);
        payload.put("status", status);
        payload.put("snapshotName", snapshotName);
        payload.put("appId", appId);
        payload.put("taskId", taskId);
        payload.put("projectPath", projectPath);
        payload.put("snapshotPath", snapshotPath);
        payload.put("source", source);
        payload.put("fileCount", fileCount);
        payload.put("createdAt", createdAt.toString());
        return payload;
    }
}
