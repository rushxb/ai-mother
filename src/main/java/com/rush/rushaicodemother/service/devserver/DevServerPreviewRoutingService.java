package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRecord;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRegistry;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/** 将预览流量解析到本地运行时或持久所有者节点。 */
@Service
public class DevServerPreviewRoutingService {

    private final DevServerManager devServerManager;
    private final DevServerSessionRegistry registry;
    private final DevServerNodeIdentityProvider identityProvider;
    private final DevServerNodeRouteResolver nodeRouteResolver;
    private final Clock clock;

    @Autowired
    public DevServerPreviewRoutingService(
            DevServerManager devServerManager,
            DevServerSessionRegistry registry,
            DevServerNodeIdentityProvider identityProvider,
            DevServerNodeRouteResolver nodeRouteResolver
    ) {
        this(devServerManager, registry, identityProvider, nodeRouteResolver, Clock.systemUTC());
    }

    DevServerPreviewRoutingService(
            DevServerManager devServerManager,
            DevServerSessionRegistry registry,
            DevServerNodeIdentityProvider identityProvider,
            DevServerNodeRouteResolver nodeRouteResolver,
            Clock clock
    ) {
        this.devServerManager = devServerManager;
        this.registry = registry;
        this.identityProvider = identityProvider;
        this.nodeRouteResolver = nodeRouteResolver;
        this.clock = clock;
    }

    public Optional<DevServerPreviewSession> findCurrent(Long appId) {
        if (appId == null || appId <= 0) {
            return Optional.empty();
        }
        Integer localPort = devServerManager.getPort(appId);
        DevServerSessionRecord record = registry.findByAppId(appId).orElse(null);
        if (hasCurrentLease(record)) {
            boolean localOwner = identityProvider.nodeId().equals(record.nodeId());
            if (!localOwner) {
                return Optional.of(new DevServerPreviewSession(
                        record.appId(),
                        record.nodeId(),
                        record.port(),
                        record.state(),
                        false,
                        record.state() == DevServerSessionState.RUNNING
                ));
            }
            boolean locallyAvailable = record.state() == DevServerSessionState.RUNNING
                    && identityProvider.ownerId().equals(record.leaseOwner())
                    && validPort(localPort)
                    && localPort == record.port();
            return Optional.of(new DevServerPreviewSession(
                    record.appId(),
                    record.nodeId(),
                    validPort(localPort) ? localPort : record.port(),
                    record.state(),
                    true,
                    locallyAvailable
            ));
        }
        if (!validPort(localPort)) {
            return Optional.empty();
        }
        return Optional.of(new DevServerPreviewSession(
                appId,
                identityProvider.nodeId(),
                localPort,
                DevServerSessionState.RUNNING,
                true,
                false
        ));
    }

    public DevServerPreviewRoute requireRunningRoute(Long appId) {
        DevServerPreviewSession session = findCurrent(appId)
                .filter(DevServerPreviewSession::running)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "Dev Server 未运行"
                ));
        if (session.local()) {
            return DevServerPreviewRoute.local(
                    appId, identityProvider.nodeId(), session.port());
        }
        return DevServerPreviewRoute.remote(
                appId,
                session.nodeId(),
                session.port(),
                nodeRouteResolver.resolve(session.nodeId())
        );
    }

    /** 验证经过身份验证的内部跃点是否已到达实际流程所有者。 */
    public int requireLocalRunningPort(Long appId) {
        DevServerSessionRecord record = registry.findByAppId(appId).orElse(null);
        Integer localPort = devServerManager.getPort(appId);
        if (!hasCurrentLease(record)
                || record.state() != DevServerSessionState.RUNNING
                || !identityProvider.nodeId().equals(record.nodeId())
                || !identityProvider.ownerId().equals(record.leaseOwner())
                || !validPort(localPort)
                || localPort != record.port()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "Dev Server owner is unavailable");
        }
        return localPort;
    }

    private boolean hasCurrentLease(DevServerSessionRecord record) {
        if (record == null || record.state() == null || !record.state().isActive()) {
            return false;
        }
        Instant leaseUntil = record.leaseUntil();
        return leaseUntil != null && leaseUntil.isAfter(clock.instant());
    }

    private boolean validPort(Integer port) {
        return port != null && port >= 1 && port <= 65535;
    }
}
