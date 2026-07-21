package com.rush.rushaicodemother.service.credit;

import com.rush.rushaicodemother.config.UserCreditProperties;
import com.rush.rushaicodemother.service.UserCreditService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationCreditSettlementCoordinatorTest {

    @Test
    void reconciliationMustRetryEveryBoundedUnsettledTerminalTask() {
        UserCreditPersistenceService persistenceService = mock(UserCreditPersistenceService.class);
        UserCreditService creditService = mock(UserCreditService.class);
        UserCreditProperties properties = new UserCreditProperties();
        properties.setSettlementBatchSize(25);
        when(persistenceService.findUnsettledTerminalTaskIds(25))
                .thenReturn(List.of("task-1", "task-2"));
        GenerationCreditSettlementCoordinator coordinator =
                new GenerationCreditSettlementCoordinator(persistenceService, creditService, properties);

        coordinator.reconcile();

        verify(creditService).chargeGenerationTask("task-1");
        verify(creditService).chargeGenerationTask("task-2");
    }
}
