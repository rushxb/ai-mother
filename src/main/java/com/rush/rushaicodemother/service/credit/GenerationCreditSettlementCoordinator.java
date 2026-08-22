package com.rush.rushaicodemother.service.credit;

import com.rush.rushaicodemother.config.UserCreditProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.trace.GenerationModelInvocationRecoveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/** 修复任务完成后幂等信用结算中断的终端任务。 */
@Slf4j
@Component
public class GenerationCreditSettlementCoordinator {

    private final UserCreditPersistenceService persistenceService;
    private final UserCreditService userCreditService;
    private final UserCreditProperties properties;
    private final GenerationModelInvocationRecoveryService invocationRecoveryService;
    private final Clock clock;

    @Autowired
    public GenerationCreditSettlementCoordinator(UserCreditPersistenceService persistenceService,
                                                 UserCreditService userCreditService,
                                                 UserCreditProperties properties,
                                                 GenerationModelInvocationRecoveryService invocationRecoveryService) {
        this(persistenceService, userCreditService, properties, invocationRecoveryService,
                Clock.systemDefaultZone());
    }

    GenerationCreditSettlementCoordinator(UserCreditPersistenceService persistenceService,
                                          UserCreditService userCreditService,
                                          UserCreditProperties properties,
                                          GenerationModelInvocationRecoveryService invocationRecoveryService,
                                          Clock clock) {
        this.persistenceService = persistenceService;
        this.userCreditService = userCreditService;
        this.properties = properties;
        this.invocationRecoveryService = invocationRecoveryService;
        this.clock = clock;
    }

    /** 对账并修复生成额度{@code Settlement}协调器状态。 */
    @Scheduled(fixedDelayString = UserCreditProperties.SETTLEMENT_SCAN_INTERVAL)
    public void reconcile() {
        recoverStaleInvocations();
        try {
            reconcileOrphanPreflightReservations();
            for (String taskId : persistenceService.findUnsettledTerminalTaskIds(
                    properties.getSettlementBatchSize())) {
                try {
                    userCreditService.chargeGenerationTask(taskId);
                } catch (RuntimeException failure) {
                    log.warn("Generation credit reconciliation failed, taskId: {}",
                            taskId, LogExceptionSanitizer.sanitize(failure));
                }
            }
        } finally {
            refreshUnsettledCount();
        }
    }

    private void reconcileOrphanPreflightReservations() {
        LocalDateTime createdBefore = LocalDateTime.now(clock)
                .minus(properties.getPreflightReservationRecoveryDelay());
        try {
            for (String taskId : persistenceService.findRecoverablePreflightReservationTaskIds(
                    createdBefore, properties.getSettlementBatchSize())) {
                try {
                    userCreditService.settleGenerationPreflight(taskId);
                } catch (RuntimeException failure) {
                    log.warn("Preflight credit reconciliation failed, taskId: {}",
                            taskId, LogExceptionSanitizer.sanitize(failure));
                }
            }
        } catch (RuntimeException scanFailure) {
            log.warn("Preflight credit reconciliation scan failed: {}",
                    LogExceptionSanitizer.sanitize(scanFailure));
        }
    }

    /** 账本扫描失败不应阻塞其他已完整任务的幂等结算。 */
    private void recoverStaleInvocations() {
        try {
            invocationRecoveryService.recoverStaleInvocations();
        } catch (RuntimeException failure) {
            log.warn("AI model invocation ledger recovery failed: {}",
                    LogExceptionSanitizer.sanitize(failure));
        }
    }

    private void refreshUnsettledCount() {
        try {
            invocationRecoveryService.refreshUnsettledInvocationCount();
        } catch (RuntimeException failure) {
            log.warn("AI model unsettled invocation metric refresh failed: {}",
                    LogExceptionSanitizer.sanitize(failure));
        }
    }
}
