package com.rush.rushaicodemother.service.devserver.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for distributed Dev Server ownership and recovery. */
public interface DevServerSessionRegistry {

    DevServerSessionClaimResult claimStarting(
            DevServerSessionRegistration registration,
            Instant now,
            Instant leaseUntil,
            int maxServersPerUser
    );

    Optional<DevServerSessionRecord> findByAppId(Long appId);

    boolean recordStartingResources(Long appId, String leaseOwner, String sandboxBackend,
                                    List<String> cleanupResourceIds, Instant now, Instant leaseUntil);

    boolean markRunning(Long appId, String leaseOwner, String sandboxBackend,
                        List<String> cleanupResourceIds, Instant now, Instant leaseUntil);

    boolean renew(Long appId, String leaseOwner, Instant now, Instant leaseUntil);

    boolean requestStop(Long appId, Instant requestedAt);

    boolean markStopping(Long appId, String leaseOwner, Instant now, Instant leaseUntil);

    boolean markStopped(Long appId, String leaseOwner, Instant stoppedAt, String reason);

    List<DevServerSessionRecord> findExpired(Instant now, int limit);

    boolean claimRecovery(DevServerSessionRecord candidate, String nodeId, String recoveryOwner,
                          Instant now, Instant leaseUntil);
}
