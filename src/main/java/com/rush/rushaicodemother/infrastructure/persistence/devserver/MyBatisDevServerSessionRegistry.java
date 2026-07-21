package com.rush.rushaicodemother.infrastructure.persistence.devserver;

import com.rush.rushaicodemother.mapper.DevServerSessionMapper;
import com.rush.rushaicodemother.model.entity.DevServerSessionEntity;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionClaimResult;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRecord;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRegistration;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRegistry;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionState;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MyBatisDevServerSessionRegistry implements DevServerSessionRegistry {

    private static final int MAX_REASON_LENGTH = 512;
    private static final String RESOURCE_SEPARATOR = "\n";

    private final DevServerSessionMapper mapper;

    @Override
    @Transactional
    public DevServerSessionClaimResult claimStarting(
            DevServerSessionRegistration registration,
            Instant now,
            Instant leaseUntil,
            int maxServersPerUser
    ) {
        validateRegistration(registration, now, leaseUntil, maxServersPerUser);
        mapper.lockUser(registration.userId());
        DevServerSessionEntity existing = mapper.selectByAppId(registration.appId());
        if (isActiveState(existing)) {
            return DevServerSessionClaimResult.ACTIVE_SESSION_EXISTS;
        }
        if (mapper.countActiveByUser(registration.userId(), toDateTime(now)) >= maxServersPerUser) {
            return DevServerSessionClaimResult.USER_QUOTA_EXCEEDED;
        }

        DevServerSessionEntity entity = startingEntity(registration, now, leaseUntil);
        try {
            if (mapper.insert(entity) > 0) {
                return DevServerSessionClaimResult.ACQUIRED;
            }
        } catch (DuplicateKeyException duplicateClaim) {
            // Another node won the app-level unique key; only a terminal row may be reused below.
        }
        int claimed = mapper.claimTerminal(
                registration.appId(),
                registration.userId(),
                registration.nodeId(),
                registration.leaseOwner(),
                registration.port(),
                registration.projectDirectory().toString(),
                toDateTime(now),
                toDateTime(leaseUntil)
        );
        return claimed > 0
                ? DevServerSessionClaimResult.ACQUIRED
                : DevServerSessionClaimResult.ACTIVE_SESSION_EXISTS;
    }

    @Override
    public Optional<DevServerSessionRecord> findByAppId(Long appId) {
        if (appId == null || appId <= 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectByAppId(appId)).map(this::toRecord);
    }

    @Override
    public boolean recordStartingResources(Long appId, String leaseOwner, String sandboxBackend,
                                           List<String> cleanupResourceIds, Instant now, Instant leaseUntil) {
        return mapper.recordStartingResources(
                appId,
                leaseOwner,
                normalizeBackend(sandboxBackend),
                encodeResourceIds(cleanupResourceIds),
                toDateTime(now),
                toDateTime(leaseUntil)
        ) > 0;
    }

    @Override
    public boolean markRunning(Long appId, String leaseOwner, String sandboxBackend,
                               List<String> cleanupResourceIds, Instant now, Instant leaseUntil) {
        return mapper.markRunning(
                appId,
                leaseOwner,
                normalizeBackend(sandboxBackend),
                encodeResourceIds(cleanupResourceIds),
                toDateTime(now),
                toDateTime(leaseUntil)
        ) > 0;
    }

    @Override
    public boolean renew(Long appId, String leaseOwner, Instant now, Instant leaseUntil) {
        return mapper.renew(appId, leaseOwner, toDateTime(now), toDateTime(leaseUntil)) > 0;
    }

    @Override
    public boolean requestStop(Long appId, Instant requestedAt) {
        return mapper.requestStop(appId, toDateTime(requestedAt)) > 0;
    }

    @Override
    public boolean markStopping(Long appId, String leaseOwner, Instant now, Instant leaseUntil) {
        return mapper.markStopping(
                appId, leaseOwner, toDateTime(now), toDateTime(leaseUntil)) > 0;
    }

    @Override
    public boolean markStopped(Long appId, String leaseOwner, Instant stoppedAt, String reason) {
        return mapper.markStopped(
                appId, leaseOwner, toDateTime(stoppedAt), truncate(reason, MAX_REASON_LENGTH)) > 0;
    }

    @Override
    public List<DevServerSessionRecord> findExpired(Instant now, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return mapper.selectExpired(toDateTime(now), limit).stream().map(this::toRecord).toList();
    }

    @Override
    public boolean claimRecovery(DevServerSessionRecord candidate, String nodeId, String recoveryOwner,
                                 Instant now, Instant leaseUntil) {
        return mapper.claimRecovery(
                candidate.appId(), candidate.version(), nodeId, recoveryOwner,
                toDateTime(now), toDateTime(leaseUntil)
        ) > 0;
    }

    private DevServerSessionEntity startingEntity(
            DevServerSessionRegistration registration,
            Instant now,
            Instant leaseUntil
    ) {
        LocalDateTime timestamp = toDateTime(now);
        return DevServerSessionEntity.builder()
                .appId(registration.appId())
                .userId(registration.userId())
                .nodeId(registration.nodeId())
                .leaseOwner(registration.leaseOwner())
                .state(DevServerSessionState.STARTING.name())
                .port(registration.port())
                .projectDirectory(registration.projectDirectory().toString())
                .leaseUntil(toDateTime(leaseUntil))
                .heartbeatAt(timestamp)
                .version(0L)
                .createTime(timestamp)
                .updateTime(timestamp)
                .build();
    }

    private DevServerSessionRecord toRecord(DevServerSessionEntity entity) {
        return new DevServerSessionRecord(
                entity.getAppId(),
                entity.getUserId(),
                entity.getNodeId(),
                entity.getLeaseOwner(),
                DevServerSessionState.valueOf(entity.getState()),
                entity.getPort(),
                Path.of(entity.getProjectDirectory()).toAbsolutePath().normalize(),
                entity.getSandboxBackend(),
                decodeResourceIds(entity.getCleanupResourceIds()),
                toInstant(entity.getLeaseUntil()),
                entity.getVersion() == null ? 0L : entity.getVersion()
        );
    }

    private boolean isActiveState(DevServerSessionEntity entity) {
        if (entity == null || entity.getState() == null) {
            return false;
        }
        DevServerSessionState state = DevServerSessionState.valueOf(entity.getState());
        return state.isActive();
    }

    private String encodeResourceIds(List<String> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return null;
        }
        List<String> normalized = resourceIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .peek(value -> {
                    if (value.contains("\n") || value.contains("\r")) {
                        throw new IllegalArgumentException("sandbox resource id cannot contain a newline");
                    }
                })
                .distinct()
                .toList();
        return normalized.isEmpty() ? null : String.join(RESOURCE_SEPARATOR, normalized);
    }

    private List<String> decodeResourceIds(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        return Arrays.stream(encoded.split(RESOURCE_SEPARATOR))
                .filter(value -> !value.isBlank())
                .toList();
    }

    private void validateRegistration(DevServerSessionRegistration registration,
                                      Instant now,
                                      Instant leaseUntil,
                                      int maxServersPerUser) {
        if (registration == null || registration.appId() == null || registration.appId() <= 0
                || registration.userId() == null || registration.userId() <= 0
                || registration.nodeId() == null || registration.nodeId().isBlank()
                || registration.leaseOwner() == null || registration.leaseOwner().isBlank()
                || registration.projectDirectory() == null
                || registration.port() < 1 || registration.port() > 65535) {
            throw new IllegalArgumentException("invalid durable Dev Server registration");
        }
        if (now == null || leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("Dev Server lease must end after now");
        }
        if (maxServersPerUser <= 0) {
            throw new IllegalArgumentException("Dev Server user quota must be positive");
        }
    }

    private String normalizeBackend(String backend) {
        return backend == null || backend.isBlank() ? "unknown" : backend.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private LocalDateTime toDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
