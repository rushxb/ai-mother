package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.infrastructure.sandbox.SandboxProcessPlan;
import com.rush.rushaicodemother.monitor.DevServerSessionMetricsCollector;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionClaimResult;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRecord;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRegistration;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRegistry;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 在数据库中断期间通过本地租赁截止日期进行持久租赁实施。 */
@Component
public class DurableDevServerSessionLeaseCoordinator
        implements DevServerSessionLeaseCoordinator, DevServerSandboxPlanListener {

    private final DevServerSessionRegistry registry;
    private final DevServerRuntimeProperties properties;
    private final DevServerNodeIdentityProvider identityProvider;
    private final DevServerSessionMetricsCollector metrics;
    private final Clock clock;
    private final Map<Long, Instant> localLeaseDeadlines = new ConcurrentHashMap<>();

    @Autowired
    public DurableDevServerSessionLeaseCoordinator(
            DevServerSessionRegistry registry,
            DevServerRuntimeProperties properties,
            DevServerNodeIdentityProvider identityProvider,
            DevServerSessionMetricsCollector metrics
    ) {
        this(registry, properties, identityProvider, metrics, Clock.systemUTC());
    }

    DurableDevServerSessionLeaseCoordinator(
            DevServerSessionRegistry registry,
            DevServerRuntimeProperties properties,
            DevServerNodeIdentityProvider identityProvider,
            Clock clock
    ) {
        this(registry, properties, identityProvider, DevServerSessionMetricsCollector.noOp(), clock);
    }

    DurableDevServerSessionLeaseCoordinator(
            DevServerSessionRegistry registry,
            DevServerRuntimeProperties properties,
            DevServerNodeIdentityProvider identityProvider,
            DevServerSessionMetricsCollector metrics,
            Clock clock
    ) {
        this.registry = registry;
        this.properties = properties;
        this.identityProvider = identityProvider;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
 * 响应计划{@code Prepared}事件。
 *
 * @param appId 应用编号
 * @param plan 计划
 */
    @Override
    public void onPlanPrepared(Long appId, SandboxProcessPlan plan) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (plan == null) {
            throw new DevServerStartException(
                    DevServerStartException.Reason.PROCESS_START_FAILED,
                    "Dev Server sandbox plan is missing"
            );
        }
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(properties.getLeaseDuration());
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            boolean recorded = registry.recordStartingResources(
                    appId,
                    identityProvider.ownerId(),
                    plan.backend(),
                    plan.cleanupResourceIds(),
                    now,
                    leaseUntil
            );
            if (!recorded) {
                throw new DevServerStartException(
                        DevServerStartException.Reason.CANCELLED,
                        "Dev Server ownership was lost before sandbox startup"
                );
            }
            localLeaseDeadlines.put(appId, leaseUntil);
        } catch (DevServerStartException ownershipFailure) {
            throw ownershipFailure;
        } catch (RuntimeException persistenceFailure) {
            throw new DevServerStartException(
                    DevServerStartException.Reason.PROCESS_START_FAILED,
                    "Failed to persist Dev Server sandbox resource manifest",
                    persistenceFailure
            );
        }
    }

    /**
 * 以原子方式声明{@code Starting}。
 *
 * @param appId 应用编号
 * @param userId 用户编号
 * @param projectDirectory 项目目录
 * @param port 端口
 * @return {@code Starting}
 */
    @Override
    public DevServerSessionClaimResult claimStarting(
            Long appId,
            Long userId,
            Path projectDirectory,
            int port
    ) {
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(properties.getLeaseDuration());
        DevServerSessionClaimResult result;
        try {
            result = registry.claimStarting(
                    new DevServerSessionRegistration(
                            appId,
                            userId,
                            identityProvider.nodeId(),
                            identityProvider.ownerId(),
                            projectDirectory.toAbsolutePath().normalize(),
                            port
                    ),
                    now,
                    leaseUntil,
                    properties.getMaxServersPerUser()
            );
        } catch (RuntimeException claimFailure) {
            metrics.recordClaim("error");
            throw claimFailure;
        }
        metrics.recordClaim(result.name());
        if (result == DevServerSessionClaimResult.ACQUIRED) {
            localLeaseDeadlines.put(appId, leaseUntil);
        }
        return result;
    }

    /**
 * 更新运行中的标记状态。
 *
 * @param appId 应用编号
 * @param sandboxBackend {@code sandboxBackend} 对应的调用参数
 * @param cleanupResourceIds 待处理的 {@code cleanupResourceIds} 集合
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean markRunning(Long appId, String sandboxBackend, List<String> cleanupResourceIds) {
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(properties.getLeaseDuration());
        boolean updated = registry.markRunning(
                appId,
                identityProvider.ownerId(),
                sandboxBackend,
                cleanupResourceIds,
                now,
                leaseUntil
        );
        if (updated) {
            localLeaseDeadlines.put(appId, leaseUntil);
        }
        return updated;
    }

    /**
 * 返回{@code renew}。
 *
 * @param appId 应用编号
 * @return 持久开发服务器会话租约协调器
 */
    @Override
    public LeaseStatus renew(Long appId) {
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(properties.getLeaseDuration());
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            if (registry.renew(appId, identityProvider.ownerId(), now, leaseUntil)) {
                localLeaseDeadlines.put(appId, leaseUntil);
                metrics.recordLeaseRenewal("renewed");
                return LeaseStatus.RENEWED;
            }
            DevServerSessionRecord current = registry.findByAppId(appId).orElse(null);
            if (current != null
                    && identityProvider.ownerId().equals(current.leaseOwner())
                    && current.state() == DevServerSessionState.STOPPING) {
                metrics.recordLeaseRenewal("stop_requested");
                return LeaseStatus.STOP_REQUESTED;
            }
            localLeaseDeadlines.remove(appId);
            metrics.recordLeaseRenewal("lost");
            return LeaseStatus.LOST;
        } catch (RuntimeException transientFailure) {
            Instant localDeadline = localLeaseDeadlines.get(appId);
            if (localDeadline == null || !now.isBefore(localDeadline)) {
                localLeaseDeadlines.remove(appId);
                metrics.recordLeaseRenewal("lost");
                return LeaseStatus.LOST;
            }
            metrics.recordLeaseRenewal("retryable_failure");
            return LeaseStatus.RETRYABLE_FAILURE;
        }
    }

    /**
 * 返回请求{@code Stop}。
 *
 * @param appId 应用编号
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean requestStop(Long appId) {
        return registry.requestStop(appId, clock.instant());
    }

    /**
 * 更新{@code Stopping}的标记状态。
 *
 * @param appId 应用编号
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean markStopping(Long appId) {
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(properties.getLeaseDuration());
        boolean updated = registry.markStopping(
                appId, identityProvider.ownerId(), now, leaseUntil);
        if (updated) {
            localLeaseDeadlines.put(appId, leaseUntil);
        }
        return updated;
    }

    /**
 * 释放持久开发服务器会话租约协调器。
 *
 * @param appId 应用编号
 * @param reason 原因
 */
    @Override
    public void release(Long appId, String reason) {
        try {
            registry.markStopped(appId, identityProvider.ownerId(), clock.instant(), reason);
        } finally {
            localLeaseDeadlines.remove(appId);
        }
    }
}
