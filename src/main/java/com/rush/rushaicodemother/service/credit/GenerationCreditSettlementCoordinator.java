package com.rush.rushaicodemother.service.credit;

import com.rush.rushaicodemother.config.UserCreditProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.service.UserCreditService;
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

    @Scheduled(fixedDelayString = "${app.user-credit.settlement-scan-interval:30s}")
    public void reconcile() {
        for (String taskId : persistenceService.findUnsettledTerminalTaskIds(
                properties.getSettlementBatchSize())) {
            try {
                userCreditService.chargeGenerationTask(taskId);
            } catch (RuntimeException failure) {
                log.warn("Generation credit reconciliation failed, taskId: {}",
                        taskId, LogExceptionSanitizer.sanitize(failure));
            }
        }
    }
}
