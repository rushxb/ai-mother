package com.rush.rushaicodemother.service.credit;

import com.rush.rushaicodemother.testing.GenerationFailureMatrix;
import com.rush.rushaicodemother.config.UserCreditProperties;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.trace.GenerationModelInvocationRecoveryService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag(GenerationFailureMatrix.TAG)
class GenerationCreditSettlementCoordinatorTest {

    @Test
    void reconciliationMustRetryEveryBoundedUnsettledTerminalTask() {
        UserCreditPersistenceService persistenceService = mock(UserCreditPersistenceService.class);
        UserCreditService creditService = mock(UserCreditService.class);
        GenerationModelInvocationRecoveryService invocationRecoveryService =
                mock(GenerationModelInvocationRecoveryService.class);
        UserCreditProperties properties = new UserCreditProperties();
        properties.setSettlementBatchSize(25);
        properties.setPreflightReservationRecoveryDelay(Duration.ofMinutes(5));
        when(persistenceService.findRecoverablePreflightReservationTaskIds(
                LocalDateTime.of(2026, 8, 21, 23, 55), 25))
                .thenReturn(List.of("preflight-orphan"));
        when(persistenceService.findUnsettledTerminalTaskIds(25))
                .thenReturn(List.of("task-1", "task-2"));
        GenerationCreditSettlementCoordinator coordinator =
                new GenerationCreditSettlementCoordinator(
                        persistenceService, creditService, properties, invocationRecoveryService,
                        Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC));

        coordinator.reconcile();

        var order = inOrder(invocationRecoveryService, creditService);
        order.verify(invocationRecoveryService).recoverStaleInvocations();
        order.verify(creditService).settleGenerationPreflight("preflight-orphan");
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
