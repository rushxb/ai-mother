package com.rush.rushaicodemother.service.credit;

import com.rush.rushaicodemother.config.UserCreditProperties;
import com.rush.rushaicodemother.model.enums.UserCreditTransactionType;
import com.rush.rushaicodemother.orchestration.delivery.GenerationCostSummary;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.CreditTransaction;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.GenerationCreditTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationTaskCostProjectionServiceTest {

    private UserCreditPersistenceService persistenceService;
    private GenerationTaskCostProjectionService projectionService;

    @BeforeEach
    void setUp() {
        persistenceService = mock(UserCreditPersistenceService.class);
        UserCreditProperties properties = new UserCreditProperties();
        properties.setTokensPerCredit(100_000L);
        projectionService = new GenerationTaskCostProjectionService(
                persistenceService,
                new ProviderCostGenerationUserBillingPolicy(),
                new UserCreditCostCalculator(properties));
    }

    @Test
    void runningProjectionMustExposeReservedBudgetAndCompletedAttemptEstimate() {
        when(persistenceService.findGenerationTask("task-running"))
                .thenReturn(new GenerationCreditTask(
                        101L, "task-running", 7L, 100L, false, null, null));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION, "task-running"))
                .thenReturn(transaction(
                        UserCreditTransactionType.GENERATION_RESERVATION,
                        "task-running", -5L, null));
        when(persistenceService.loadTaskProviderCostObservation("task-running"))
                .thenReturn(new ProviderCostObservation(120_000L, 0L, 20_000L, 0L, 1L));

        GenerationCostSummary summary = projectionService.project("task-running", false);

        assertEquals("reserved", summary.settlementStatus());
        assertEquals(5L, summary.maximumReservedCredit());
        assertEquals(140_000L, summary.providerObservedTokens());
        assertEquals(2L, summary.provisionalCreditCost());
        assertEquals(20_000L, summary.waivedTokens());
        assertEquals("provider_timeout", summary.waiverReason());
    }

    @Test
    void settledProjectionMustExposeActualChargeRefundAndWaiverReason() {
        when(persistenceService.findGenerationTask("task-settled"))
                .thenReturn(new GenerationCreditTask(
                        102L, "task-settled", 7L, 100L, true, 2L, 120_000L));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION, "task-settled"))
                .thenReturn(transaction(
                        UserCreditTransactionType.GENERATION_RESERVATION,
                        "task-settled", -5L, null));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_SETTLEMENT, "task-settled"))
                .thenReturn(transaction(
                        UserCreditTransactionType.GENERATION_SETTLEMENT,
                        "task-settled", 3L, 120_000L));
        when(persistenceService.loadTaskProviderCostObservation("task-settled"))
                .thenReturn(new ProviderCostObservation(120_000L, 0L, 20_000L, 0L, 0L));

        GenerationCostSummary summary = projectionService.project("task-settled", true);

        assertEquals("settled", summary.settlementStatus());
        assertEquals(120_000L, summary.totalTokens());
        assertEquals(2L, summary.creditCost());
        assertEquals(true, summary.charged());
        assertEquals(3L, summary.refundedCredit());
        assertEquals("actual_cost_below_reserved", summary.refundReason());
        assertEquals(20_000L, summary.waivedTokens());
        assertEquals("provider_timeout", summary.waiverReason());
    }

    @Test
    void zeroChargeMustRemainExplicitWhenProviderFailureIsWaived() {
        when(persistenceService.findGenerationTask("task-waived"))
                .thenReturn(new GenerationCreditTask(
                        103L, "task-waived", 7L, 100L, true, 0L, 0L));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION, "task-waived"))
                .thenReturn(transaction(
                        UserCreditTransactionType.GENERATION_RESERVATION,
                        "task-waived", -2L, null));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_SETTLEMENT, "task-waived"))
                .thenReturn(transaction(
                        UserCreditTransactionType.GENERATION_SETTLEMENT,
                        "task-waived", 2L, 0L));
        when(persistenceService.loadTaskProviderCostObservation("task-waived"))
                .thenReturn(new ProviderCostObservation(0L, 0L, 0L, 50_000L, 0L));

        GenerationCostSummary summary = projectionService.project("task-waived", true);

        assertFalse(summary.charged());
        assertEquals(0L, summary.creditCost());
        assertEquals(2L, summary.refundedCredit());
        assertEquals(50_000L, summary.waivedTokens());
        assertEquals("provider_failure", summary.waiverReason());
    }

    @Test
    void settledProjectionMustFailClosedWhenLedgerDeltaConflictsWithTaskFacts() {
        when(persistenceService.findGenerationTask("task-conflict"))
                .thenReturn(new GenerationCreditTask(
                        104L, "task-conflict", 7L, 100L, true, 2L, 120_000L));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION, "task-conflict"))
                .thenReturn(transaction(
                        UserCreditTransactionType.GENERATION_RESERVATION,
                        "task-conflict", -5L, null));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_SETTLEMENT, "task-conflict"))
                .thenReturn(transaction(
                        UserCreditTransactionType.GENERATION_SETTLEMENT,
                        "task-conflict", 2L, 120_000L));
        when(persistenceService.loadTaskProviderCostObservation("task-conflict"))
                .thenReturn(new ProviderCostObservation(120_000L, 0L, 0L, 0L, 0L));

        assertThrows(IllegalStateException.class,
                () -> projectionService.project("task-conflict", true));
    }

    private CreditTransaction transaction(UserCreditTransactionType type,
                                          String taskId,
                                          long changeAmount,
                                          Long tokenCount) {
        return new CreditTransaction(
                7L, 100L, changeAmount, 100L, type, taskId,
                "不参与成本事实推导的备注", null, tokenCount);
    }
}
