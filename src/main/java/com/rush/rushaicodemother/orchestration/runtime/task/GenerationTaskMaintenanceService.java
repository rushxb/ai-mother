package com.rush.rushaicodemother.orchestration.runtime.task;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** 从一个进程范围的维护滴答声中运行检测信号和恢复。 */
@Service
public class GenerationTaskMaintenanceService {

    private final GenerationTaskLeaseCoordinator leaseCoordinator;
    private final GenerationTaskRecoveryService recoveryService;
    private final GenerationTaskLeaseProperties properties;
    private final Clock clock;
    private final AtomicReference<Instant> nextRecoveryAt = new AtomicReference<>();

    @Autowired
    public GenerationTaskMaintenanceService(GenerationTaskLeaseCoordinator leaseCoordinator,
                                            GenerationTaskRecoveryService recoveryService,
                                            GenerationTaskLeaseProperties properties) {
        this(leaseCoordinator, recoveryService, properties, Clock.systemUTC());
    }

    GenerationTaskMaintenanceService(GenerationTaskLeaseCoordinator leaseCoordinator,
                                     GenerationTaskRecoveryService recoveryService,
                                     GenerationTaskLeaseProperties properties,
                                     Clock clock) {
        this.leaseCoordinator = leaseCoordinator;
        this.recoveryService = recoveryService;
        this.properties = properties;
        this.clock = clock;
        this.nextRecoveryAt.set(clock.instant());
    }

    /** 运行{@code Maintenance}处理流程。 */
    public void runMaintenance() {
        leaseCoordinator.heartbeatTrackedTasks();
        Instant now = clock.instant();
        Instant due = nextRecoveryAt.get();
        if (!now.isBefore(due)
                && nextRecoveryAt.compareAndSet(due, now.plus(properties.getRecoveryScanInterval()))) {
            recoveryService.recoverExpiredTasks();
        }
    }
}
