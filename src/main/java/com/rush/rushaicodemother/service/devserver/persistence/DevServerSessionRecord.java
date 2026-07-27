package com.rush.rushaicodemother.service.devserver.persistence;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** 用于所有权检查和孤立恢复的持久快照。 */
public record DevServerSessionRecord(
        Long appId,
        Long userId,
        String nodeId,
        String leaseOwner,
        DevServerSessionState state,
        int port,
        Path projectDirectory,
        String sandboxBackend,
        List<String> cleanupResourceIds,
        Instant leaseUntil,
        long version
) {
    public DevServerSessionRecord {
        cleanupResourceIds = cleanupResourceIds == null ? List.of() : List.copyOf(cleanupResourceIds);
    }
}
