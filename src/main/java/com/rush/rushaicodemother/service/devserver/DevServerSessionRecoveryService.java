package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.infrastructure.sandbox.GeneratedCodeProcessSandbox;
import com.rush.rushaicodemother.monitor.DevServerSessionMetricsCollector;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRecord;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Claims expired durable sessions and removes their sandbox resources idempotently. */
@Slf4j
@Service
public class DevServerSessionRecoveryService {

    private final DevServerSessionRegistry registry;
    private final DevServerRuntimeProperties properties;
    private final DevServerNodeIdentityProvider identityProvider;
    private final GeneratedCodeProcessSandbox processSandbox;
    private final DevServerSessionMetricsCollector metrics;
    private final Clock clock;

    @Autowired
    public DevServerSessionRecoveryService(
            DevServerSessionRegistry registry,
            DevServerRuntimeProperties properties,
            DevServerNodeIdentityProvider identityProvider,
            GeneratedCodeProcessSandbox processSandbox,
            DevServerSessionMetricsCollector metrics
    ) {
        this(registry, properties, identityProvider, processSandbox, metrics, Clock.systemUTC());
    }

    DevServerSessionRecoveryService(
            DevServerSessionRegistry registry,
            DevServerRuntimeProperties properties,
            DevServerNodeIdentityProvider identityProvider,
            GeneratedCodeProcessSandbox processSandbox,
            Clock clock
    ) {
        this(registry, properties, identityProvider, processSandbox,
                DevServerSessionMetricsCollector.noOp(), clock);
    }

    DevServerSessionRecoveryService(
            DevServerSessionRegistry registry,
            DevServerRuntimeProperties properties,
            DevServerNodeIdentityProvider identityProvider,
            GeneratedCodeProcessSandbox processSandbox,
            DevServerSessionMetricsCollector metrics,
            Clock clock
    ) {
        this.registry = registry;
        this.properties = properties;
        this.identityProvider = identityProvider;
        this.processSandbox = processSandbox;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.dev-server.runtime.recovery-scan-interval:15s}")
    public int recoverExpiredSessions() {
        Instant now = clock.instant();
        List<DevServerSessionRecord> candidates = registry.findExpired(
                now, properties.getRecoveryBatchSize());
        int recovered = 0;
        for (DevServerSessionRecord candidate : candidates) {
            Instant leaseUntil = clock.instant().plus(properties.getLeaseDuration());
            if (!registry.claimRecovery(
                    candidate,
                    identityProvider.nodeId(),
                    identityProvider.ownerId(),
                    clock.instant(),
                    leaseUntil
            )) {
                continue;
            }
            metrics.recordRecovery(candidate.sandboxBackend(), "claimed");
            try {
                processSandbox.cleanupResources(
                        candidate.sandboxBackend(), candidate.cleanupResourceIds());
                boolean terminalized = registry.markStopped(
                        candidate.appId(),
                        identityProvider.ownerId(),
                        clock.instant(),
                        "expired_session_recovered"
                );
                if (!terminalized) {
                    metrics.recordRecovery(candidate.sandboxBackend(), "terminalize_lost");
                    continue;
                }
                recovered++;
                metrics.recordRecovery(candidate.sandboxBackend(), "success");
                log.warn(
                        "Recovered expired Dev Server session, appId={}, previousNode={}, resources={}",
                        candidate.appId(), candidate.nodeId(), candidate.cleanupResourceIds().size()
                );
            } catch (RuntimeException recoveryFailure) {
                metrics.recordRecovery(candidate.sandboxBackend(), "cleanup_failed");
                log.error(
                        "Failed to recover expired Dev Server session, appId={}",
                        candidate.appId(),
                        LogExceptionSanitizer.sanitize(recoveryFailure)
                );
            }
        }
        return recovered;
    }
}
