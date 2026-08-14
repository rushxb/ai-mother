package com.rush.rushaicodemother.service.credit;

import com.rush.rushaicodemother.config.UserCreditProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.trace.GenerationModelInvocationRecoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 修复任务完成后幂等信用结算中断的终端任务。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationCreditSettlementCoordinator {

    private final UserCreditPersistenceService persistenceService;
    private final UserCreditService userCreditService;
    private final UserCreditProperties properties;
    private final GenerationModelInvocationRecoveryService invocationRecoveryService;

    /** 对账并修复生成额度{@code Settlement}协调器状态。 */
    @Scheduled(fixedDelayString = UserCreditProperties.SETTLEMENT_SCAN_INTERVAL)
    public void reconcile() {
        recoverStaleInvocations();
        try {
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
