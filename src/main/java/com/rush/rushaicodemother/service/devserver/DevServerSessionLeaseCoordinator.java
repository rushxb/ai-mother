package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionClaimResult;

import java.nio.file.Path;
import java.util.List;

/** Cross-node ownership boundary used by the in-process lifecycle manager. */
public interface DevServerSessionLeaseCoordinator {

    enum LeaseStatus {
        RENEWED,
        STOP_REQUESTED,
        RETRYABLE_FAILURE,
        LOST
    }

    default DevServerSessionClaimResult claimStarting(
            Long appId,
            Long userId,
            Path projectDirectory,
            int port
    ) {
        return DevServerSessionClaimResult.ACQUIRED;
    }

    default boolean markRunning(Long appId, String sandboxBackend, List<String> cleanupResourceIds) {
        return true;
    }

    default LeaseStatus renew(Long appId) {
        return LeaseStatus.RENEWED;
    }

    default boolean requestStop(Long appId) {
        return false;
    }

    default boolean markStopping(Long appId) {
        return true;
    }

    default void release(Long appId, String reason) {
    }

    static DevServerSessionLeaseCoordinator noOp() {
        return new DevServerSessionLeaseCoordinator() {
        };
    }
}
