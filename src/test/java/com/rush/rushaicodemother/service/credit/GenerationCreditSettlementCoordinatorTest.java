package com.rush.rushaicodemother.service.credit;

import com.rush.rushaicodemother.config.UserCreditProperties;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.trace.GenerationModelInvocationRecoveryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationCreditSettlementCoordinatorTest {

    @Test
    void reconciliationMustRetryEveryBoundedUnsettledTerminalTask() {
        UserCreditPersistenceService persistenceService = mock(UserCreditPersistenceService.class);
        UserCreditService creditService = mock(UserCreditService.class);
        GenerationModelInvocationRecoveryService invocationRecoveryService =
                mock(GenerationModelInvocationRecoveryService.class);
        UserCreditProperties properties = new UserCreditProperties();
        properties.setSettlementBatchSize(25);
        when(persistenceService.findUnsettledTerminalTaskIds(25))
                .thenReturn(List.of("task-1", "task-2"));
        GenerationCreditSettlementCoordinator coordinator =
                new GenerationCreditSettlementCoordinator(
                        persistenceService, creditService, properties, invocationRecoveryService);

        coordinator.reconcile();

        var order = inOrder(invocationRecoveryService, creditService);
        order.verify(invocationRecoveryService).recoverStaleInvocations();
        order.verify(creditService).chargeGenerationTask("task-1");
        order.verify(creditService).chargeGenerationTask("task-2");
        verify(invocationRecoveryService).refreshUnsettledInvocationCount();
    }

    @Test
    void recoveryFailureMustNotBlockSettlementOfAlreadyCompleteTasks() {
        UserCreditPersistenceService persistenceService = mock(UserCreditPersistenceService.class);
        UserCreditService creditService = mock(UserCreditService.class);
        GenerationModelInvocationRecoveryService invocationRecoveryService =
                mock(GenerationModelInvocationRecoveryService.class);
        UserCreditProperties properties = new UserCreditProperties();
        properties.setSettlementBatchSize(25);
        doThrow(new IllegalStateException("ledger unavailable"))
                .when(invocationRecoveryService).recoverStaleInvocations();
        when(persistenceService.findUnsettledTerminalTaskIds(25))
                .thenReturn(List.of("task-complete"));
        GenerationCreditSettlementCoordinator coordinator =
                new GenerationCreditSettlementCoordinator(
                        persistenceService, creditService, properties, invocationRecoveryService);

        coordinator.reconcile();

        verify(creditService).chargeGenerationTask("task-complete");
        verify(invocationRecoveryService).refreshUnsettledInvocationCount();
    }
}
