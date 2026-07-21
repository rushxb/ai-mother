package com.rush.rushaicodemother.service.devserver.persistence;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Durable snapshot used for ownership checks and orphan recovery. */
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
