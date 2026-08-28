package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.testing.GenerationFailureMatrix;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.UserCreditTransactionType;
import com.rush.rushaicodemother.monitor.GenerationCreditMetricsCollector;
import com.rush.rushaicodemother.service.credit.AdminCreditAdjustmentCommand;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationCommand;
import com.rush.rushaicodemother.service.credit.ProviderCostGenerationUserBillingPolicy;
import com.rush.rushaicodemother.service.credit.ProviderCostObservation;
import com.rush.rushaicodemother.service.credit.UserCreditCostCalculator;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.CreditAccount;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.CreditTransaction;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.GenerationCreditTask;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.NewCreditTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag(GenerationFailureMatrix.TAG)
class UserCreditServiceImplTest {

    private static final String REQUEST_ID = "550e8400-e29b-41d4-a716-446655440000";

    private UserCreditPersistenceService persistenceService;
    private UserCreditCostCalculator costCalculator;
    private UserCreditServiceImpl creditService;
    private GenerationCreditMetricsCollector creditMetricsCollector;

    @BeforeEach
    void setUp() {
        persistenceService = mock(UserCreditPersistenceService.class);
        costCalculator = mock(UserCreditCostCalculator.class);
        creditMetricsCollector = mock(GenerationCreditMetricsCollector.class);
        creditService = new UserCreditServiceImpl(
                persistenceService,
                costCalculator,
                creditMetricsCollector,
                new ProviderCostGenerationUserBillingPolicy());
    }

    @Test
    void ensureHasCreditMustRejectInvalidMissingAndEmptyAccounts() {
        BusinessException invalid = assertThrows(
                BusinessException.class,
                () -> creditService.ensureHasCredit(null)
        );
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), invalid.getCode());
        verifyNoInteractions(persistenceService);

        when(persistenceService.findActiveAccount(7L)).thenReturn(null);
        BusinessException missing = assertThrows(
                BusinessException.class,
                () -> creditService.ensureHasCredit(7L)
        );
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), missing.getCode());

        when(persistenceService.findActiveAccount(7L)).thenReturn(new CreditAccount(7L, 0L));
        BusinessException empty = assertThrows(
                BusinessException.class,
                () -> creditService.ensureHasCredit(7L)
        );
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), empty.getCode());
    }

    @Test
    void ensureHasCreditMustAcceptPositiveBalance() {
        when(persistenceService.findActiveAccount(7L)).thenReturn(new CreditAccount(7L, 1L));

        creditService.ensureHasCredit(7L);

        verify(persistenceService).findActiveAccount(7L);
    }

    @Test
    void ensureHasCreditMustEnforceRequestedUpperBound() {
        when(persistenceService.findActiveAccount(7L)).thenReturn(new CreditAccount(7L, 8L));

        BusinessException insufficient = assertThrows(
                BusinessException.class,
                () -> creditService.ensureHasCredit(7L, 9L));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), insufficient.getCode());
        assertTrue(insufficient.getMessage().contains("9"));
    }

    @Test
    void initializeCreditMustWriteDedicatedIdempotentLedger() {
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 0L));

        creditService.initializeCredit(7L, 30L, 9L);

        verify(persistenceService).updateBalance(7L, 30L);
        ArgumentCaptor<NewCreditTransaction> captor =
                ArgumentCaptor.forClass(NewCreditTransaction.class);
        verify(persistenceService).appendTransaction(captor.capture());
        NewCreditTransaction transaction = captor.getValue();
        assertEquals(7L, transaction.userId());
        assertEquals(30L, transaction.changeAmount());
        assertEquals(30L, transaction.balanceAfter());
        assertEquals(UserCreditTransactionType.ACCOUNT_INITIALIZATION, transaction.type());
        assertEquals("7", transaction.bizId());
        assertEquals("管理员创建用户初始化积分", transaction.remark());
        assertEquals(9L, transaction.adminUserId());
        assertNull(transaction.tokenCount());
    }

    @Test
    void initializeCreditRetryMustNotApplyBalanceTwice() {
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 30L));
        when(persistenceService.findTransaction(UserCreditTransactionType.ACCOUNT_INITIALIZATION, "7"))
                .thenReturn(new CreditTransaction(
                        7L, 30L, 30L, UserCreditTransactionType.ACCOUNT_INITIALIZATION,
                        "7", "管理员创建用户初始化积分", 9L, null
                ));

        creditService.initializeCredit(7L, 30L, 9L);

        verify(persistenceService, never()).updateBalance(anyLong(), anyLong());
        verify(persistenceService, never()).appendTransaction(any());
    }

    @Test
    void adminAdjustmentMustNormalizeInputAndWriteRequestIdLedger() {
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 5L));

        long balanceAfter = creditService.adjustCreditByAdmin(new AdminCreditAdjustmentCommand(
                REQUEST_ID.toUpperCase(),
                7L,
                2L,
                "  correction  ",
                9L
        ));

        assertEquals(7L, balanceAfter);
        verify(persistenceService).updateBalance(7L, 7L);
        ArgumentCaptor<NewCreditTransaction> captor =
                ArgumentCaptor.forClass(NewCreditTransaction.class);
        verify(persistenceService).appendTransaction(captor.capture());
        NewCreditTransaction transaction = captor.getValue();
        assertEquals(REQUEST_ID, transaction.bizId());
        assertEquals("correction", transaction.remark());
        assertEquals(UserCreditTransactionType.ADMIN_ADJUST, transaction.type());
        assertEquals(9L, transaction.adminUserId());
    }

    @Test
    void repeatedAdminRequestMustReturnPersistedBalanceWithoutSecondWrite() {
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 7L));
        when(persistenceService.findTransaction(UserCreditTransactionType.ADMIN_ADJUST, REQUEST_ID))
                .thenReturn(new CreditTransaction(
                        7L, 2L, 7L, UserCreditTransactionType.ADMIN_ADJUST,
                        REQUEST_ID, "correction", 9L, null
                ));

        long balanceAfter = creditService.adjustCreditByAdmin(adjustment(2L, "correction"));

        assertEquals(7L, balanceAfter);
        verify(persistenceService, never()).updateBalance(anyLong(), anyLong());
        verify(persistenceService, never()).appendTransaction(any());
    }

    @Test
    void generationReservationMustFreezeQuotedCreditWithAnIdempotentLedger() {
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 10L));

        creditService.reserveGenerationTask(reservationCommand(3L, "policy-v1:LIGHT_EDIT:VUE_PROJECT"));

        verify(persistenceService).updateBalance(7L, 7L);
        ArgumentCaptor<NewCreditTransaction> captor =
                ArgumentCaptor.forClass(NewCreditTransaction.class);
        verify(persistenceService).appendTransaction(captor.capture());
        NewCreditTransaction transaction = captor.getValue();
        assertEquals(-3L, transaction.changeAmount());
        assertEquals(7L, transaction.balanceAfter());
        assertEquals(UserCreditTransactionType.GENERATION_RESERVATION, transaction.type());
        assertEquals("task-1", transaction.bizId());
        assertEquals("reservation:policy-v1:LIGHT_EDIT:VUE_PROJECT", transaction.remark());
        assertEquals(100L, transaction.tenantId());
        assertNull(transaction.tokenCount());
    }

    @Test
    void repeatedGenerationReservationMustNotFreezeCreditTwice() {
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 7L));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION, "task-1"))
                .thenReturn(new CreditTransaction(
                        7L, 100L, -3L, 7L, UserCreditTransactionType.GENERATION_RESERVATION,
                        "task-1", "reservation:policy-v1:LIGHT_EDIT:VUE_PROJECT", null, null
                ));

        creditService.reserveGenerationTask(reservationCommand(3L, "policy-v1:LIGHT_EDIT:VUE_PROJECT"));

        verify(persistenceService, never()).updateBalance(anyLong(), anyLong());
        verify(persistenceService, never()).appendTransaction(any());
    }

    @Test
    void preflightReservationMustUseARecoverableLedgerMarker() {
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 10L));

        creditService.reserveGenerationPreflight(
                reservationCommand(5L, "policy-v1:PREFLIGHT_MAX:VUE_PROJECT"));

        ArgumentCaptor<NewCreditTransaction> captor =
                ArgumentCaptor.forClass(NewCreditTransaction.class);
        verify(persistenceService).appendTransaction(captor.capture());
        assertEquals(-5L, captor.getValue().changeAmount());
        assertEquals("reservation:preflight:policy-v1:PREFLIGHT_MAX:VUE_PROJECT",
                captor.getValue().remark());
    }

    @Test
    void finalTaskReservationMustAdoptALargerPreflightReservation() {
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 5L));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION, "task-1"))
                .thenReturn(new CreditTransaction(
                        7L, 100L, -5L, 5L, UserCreditTransactionType.GENERATION_RESERVATION,
                        "task-1", "reservation:preflight:policy-v1:PREFLIGHT_MAX:VUE_PROJECT",
                        null, null));

        creditService.reserveGenerationTask(
                reservationCommand(3L, "policy-v1:LIGHT_EDIT:VUE_PROJECT"));

        verify(persistenceService, never()).updateBalance(anyLong(), anyLong());
        verify(persistenceService, never()).appendTransaction(any());
    }

    @Test
    void finalTaskMustRejectAPreflightReservationThatWasAlreadyReleased() {
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 10L));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION, "task-1"))
                .thenReturn(new CreditTransaction(
                        7L, 100L, -5L, 5L, UserCreditTransactionType.GENERATION_RESERVATION,
                        "task-1", "reservation:preflight:policy-v1:PREFLIGHT_MAX:VUE_PROJECT",
                        null, null));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_SETTLEMENT, "task-1"))
                .thenReturn(new CreditTransaction(
                        7L, 100L, 5L, 10L, UserCreditTransactionType.GENERATION_SETTLEMENT,
                        "task-1", "released", null, 0L));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> creditService.reserveGenerationTask(
                        reservationCommand(3L, "policy-v1:LIGHT_EDIT:VUE_PROJECT")));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), failure.getCode());
        verify(persistenceService, never()).updateBalance(anyLong(), anyLong());
        verify(persistenceService, never()).appendTransaction(any());
    }

    @Test
    void orphanPreflightReservationMustSettleObservedUsageAndRefundTheRemainder() {
        when(persistenceService.lockGenerationTask("task-1")).thenReturn(null);
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION, "task-1"))
                .thenReturn(new CreditTransaction(
                        7L, 100L, -5L, 5L, UserCreditTransactionType.GENERATION_RESERVATION,
                        "task-1", "reservation:preflight:policy-v1:PREFLIGHT_MAX:VUE_PROJECT",
                        null, null));
        when(persistenceService.loadTaskProviderCostObservation("task-1"))
                .thenReturn(new ProviderCostObservation(100_000L, 0L, 0L, 0L, 0L));
        when(costCalculator.calculate(100_000L)).thenReturn(1L);
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 5L));
        when(persistenceService.findGenerationTask("task-1")).thenReturn(null);

        creditService.settleGenerationPreflight("task-1");

        verify(persistenceService).updateBalance(7L, 9L);
        ArgumentCaptor<NewCreditTransaction> captor =
                ArgumentCaptor.forClass(NewCreditTransaction.class);
        verify(persistenceService).appendTransaction(captor.capture());
        NewCreditTransaction settlement = captor.getValue();
        assertEquals(UserCreditTransactionType.GENERATION_SETTLEMENT, settlement.type());
        assertEquals(4L, settlement.changeAmount());
        assertEquals(9L, settlement.balanceAfter());
        assertEquals(100_000L, settlement.tokenCount());
        verify(persistenceService, never()).settleGenerationTask(anyLong(), anyLong(), anyLong());
    }

    @Test
    void preflightSettlementMustLeaveAnAlreadyCreatedTaskToTaskSettlement() {
        when(persistenceService.lockGenerationTask("task-1"))
                .thenReturn(generationTask(false));

        creditService.settleGenerationPreflight("task-1");

        verify(persistenceService).lockGenerationTask("task-1");
        verify(persistenceService, never()).findTransaction(any(), any());
        verify(persistenceService, never()).lockActiveAccount(any());
    }

    @Test
    void generationReservationMustRejectInsufficientBalanceBeforeDurableSubmission() {
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 2L));

        BusinessException failure = assertThrows(
                BusinessException.class,
                () -> creditService.reserveGenerationTask(
                        reservationCommand(3L, "policy-v1:LIGHT_EDIT:VUE_PROJECT"))
        );

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), failure.getCode());
        verify(persistenceService, never()).updateBalance(anyLong(), anyLong());
        verify(persistenceService, never()).appendTransaction(any());
    }

    @Test
    void reusedRequestIdWithDifferentPayloadMustFailExplicitly() {
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 7L));
        when(persistenceService.findTransaction(UserCreditTransactionType.ADMIN_ADJUST, REQUEST_ID))
                .thenReturn(new CreditTransaction(
                        7L, 1L, 6L, UserCreditTransactionType.ADMIN_ADJUST,
                        REQUEST_ID, "different", 9L, null
                ));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> creditService.adjustCreditByAdmin(adjustment(2L, "correction"))
        );

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        verify(persistenceService, never()).updateBalance(anyLong(), anyLong());
    }

    @Test
    void adminAdjustmentMustRejectInvalidInputOverflowAndInsufficientBalance() {
        BusinessException invalidRequestId = assertThrows(
                BusinessException.class,
                () -> creditService.adjustCreditByAdmin(new AdminCreditAdjustmentCommand(
                        "invalid", 7L, 1L, "reason", 9L
                ))
        );
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), invalidRequestId.getCode());
        verifyNoInteractions(persistenceService);

        when(persistenceService.lockActiveAccount(7L))
                .thenReturn(new CreditAccount(7L, Long.MAX_VALUE));
        BusinessException overflow = assertThrows(
                BusinessException.class,
                () -> creditService.adjustCreditByAdmin(adjustment(1L, "overflow"))
        );
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), overflow.getCode());

        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 5L));
        BusinessException insufficient = assertThrows(
                BusinessException.class,
                () -> creditService.adjustCreditByAdmin(adjustment(-6L, "deduct"))
        );
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), insufficient.getCode());
    }

    @Test
    void settledGenerationTaskMustRemainIdempotent() {
        when(persistenceService.lockGenerationTask("task-1"))
                .thenReturn(generationTask(true));

        creditService.chargeGenerationTask("task-1");

        verify(persistenceService).lockGenerationTask("task-1");
        verify(persistenceService, never()).findTransaction(any(), any());
        verifyNoInteractions(costCalculator);
    }

    @Test
    void generationChargeMustCapCostAtBalanceAndPersistOneAuditableSettlement() {
        when(persistenceService.lockGenerationTask("task-1"))
                .thenReturn(generationTask(false));
        when(persistenceService.loadTaskProviderCostObservation("task-1"))
                .thenReturn(new ProviderCostObservation(200_001L, 0L, 0L, 0L, 0L));
        when(costCalculator.calculate(200_001L)).thenReturn(3L);
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 2L));

        creditService.chargeGenerationTask("task-1");

        verify(persistenceService).updateBalance(7L, 0L);
        ArgumentCaptor<NewCreditTransaction> captor =
                ArgumentCaptor.forClass(NewCreditTransaction.class);
        verify(persistenceService).appendTransaction(captor.capture());
        NewCreditTransaction transaction = captor.getValue();
        assertEquals(-2L, transaction.changeAmount());
        assertEquals(0L, transaction.balanceAfter());
        assertEquals("task-1", transaction.bizId());
        assertEquals(200_001L, transaction.tokenCount());
        assertEquals(UserCreditTransactionType.GENERATION_CHARGE, transaction.type());
        assertEquals(100L, transaction.tenantId());
        verify(persistenceService).settleGenerationTask(101L, 2L, 200_001L);
    }

    @Test
    void reservedGenerationSettlementMustRefundUnusedCredit() {
        when(persistenceService.lockGenerationTask("task-1"))
                .thenReturn(generationTask(false));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION, "task-1"))
                .thenReturn(new CreditTransaction(
                        7L, 100L, -5L, 5L, UserCreditTransactionType.GENERATION_RESERVATION,
                        "task-1", "reservation:policy-v1", null, null
                ));
        when(persistenceService.loadTaskProviderCostObservation("task-1"))
                .thenReturn(new ProviderCostObservation(100_000L, 0L, 0L, 0L, 0L));
        when(costCalculator.calculate(100_000L)).thenReturn(1L);
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 5L));

        creditService.chargeGenerationTask("task-1");

        verify(persistenceService).updateBalance(7L, 9L);
        ArgumentCaptor<NewCreditTransaction> captor =
                ArgumentCaptor.forClass(NewCreditTransaction.class);
        verify(persistenceService).appendTransaction(captor.capture());
        NewCreditTransaction settlement = captor.getValue();
        assertEquals(UserCreditTransactionType.GENERATION_SETTLEMENT, settlement.type());
        assertEquals(4L, settlement.changeAmount());
        assertEquals(9L, settlement.balanceAfter());
        assertEquals(100_000L, settlement.tokenCount());
        assertEquals(100L, settlement.tenantId());
        verify(persistenceService).settleGenerationTask(101L, 1L, 100_000L);
    }

    @Test
    void reservedGenerationSettlementMustCaptureAvailableOverageWithoutNegativeBalance() {
        when(persistenceService.lockGenerationTask("task-1"))
                .thenReturn(generationTask(false));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION, "task-1"))
                .thenReturn(new CreditTransaction(
                        7L, 100L, -2L, 1L, UserCreditTransactionType.GENERATION_RESERVATION,
                        "task-1", "reservation:policy-v1", null, null
                ));
        when(persistenceService.loadTaskProviderCostObservation("task-1"))
                .thenReturn(new ProviderCostObservation(500_000L, 0L, 0L, 0L, 0L));
        when(costCalculator.calculate(500_000L)).thenReturn(5L);
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 1L));

        creditService.chargeGenerationTask("task-1");

        verify(persistenceService).updateBalance(7L, 0L);
        ArgumentCaptor<NewCreditTransaction> captor =
                ArgumentCaptor.forClass(NewCreditTransaction.class);
        verify(persistenceService).appendTransaction(captor.capture());
        NewCreditTransaction settlement = captor.getValue();
        assertEquals(-1L, settlement.changeAmount());
        assertEquals(0L, settlement.balanceAfter());
        verify(persistenceService).settleGenerationTask(101L, 3L, 500_000L);
    }

    @Test
    void cancelledBeforeModelUseMustReleaseTheEntireReservation() {
        when(persistenceService.lockGenerationTask("task-1"))
                .thenReturn(generationTask(false));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION, "task-1"))
                .thenReturn(new CreditTransaction(
                        7L, 100L, -3L, 7L, UserCreditTransactionType.GENERATION_RESERVATION,
                        "task-1", "reservation:policy-v1", null, null
                ));
        when(costCalculator.calculate(0L)).thenReturn(0L);
        when(persistenceService.loadTaskProviderCostObservation("task-1"))
                .thenReturn(ProviderCostObservation.none());
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 7L));

        creditService.chargeGenerationTask("task-1");

        verify(persistenceService).updateBalance(7L, 10L);
        verify(persistenceService).settleGenerationTask(101L, 0L, 0L);
    }

    @Test
    void userCancelledProviderAttemptMustBeChargedWhileFailuresStayWaivedAndAuditable() {
        when(persistenceService.lockGenerationTask("task-1"))
                .thenReturn(generationTask(false));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION, "task-1"))
                .thenReturn(new CreditTransaction(
                        7L, 100L, -3L, 7L, UserCreditTransactionType.GENERATION_RESERVATION,
                        "task-1", "reservation:policy-v1", null, null
                ));
        when(persistenceService.loadTaskProviderCostObservation("task-1"))
                .thenReturn(new ProviderCostObservation(
                        0L, 100_000L, 200_000L, 300_000L, 0L));
        when(costCalculator.calculate(100_000L)).thenReturn(1L);
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 7L));

        creditService.chargeGenerationTask("task-1");

        verify(persistenceService).updateBalance(7L, 9L);
        ArgumentCaptor<NewCreditTransaction> captor =
                ArgumentCaptor.forClass(NewCreditTransaction.class);
        verify(persistenceService).appendTransaction(captor.capture());
        NewCreditTransaction settlement = captor.getValue();
        assertEquals(100_000L, settlement.tokenCount());
        assertTrue(settlement.remark().contains("providerTokens=600000"));
        assertTrue(settlement.remark().contains("waivedTokens=500000"));
        assertTrue(settlement.remark().contains("policy=provider-cost-v1"));
        verify(persistenceService).settleGenerationTask(101L, 1L, 100_000L);
        verify(creditMetricsCollector).recordProviderCostSettlement(
                600_000L, 100_000L, 500_000L);
    }

    @Test
    void incompleteProviderCostMustRemainPendingWithoutRefundingReservation() {
        when(persistenceService.lockGenerationTask("task-1"))
                .thenReturn(generationTask(false));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION, "task-1"))
                .thenReturn(new CreditTransaction(
                        7L, 100L, -3L, 7L, UserCreditTransactionType.GENERATION_RESERVATION,
                        "task-1", "reservation:policy-v1", null, null
                ));
        when(persistenceService.loadTaskProviderCostObservation("task-1"))
                .thenReturn(new ProviderCostObservation(0L, 0L, 0L, 0L, 1L));

        BusinessException failure = assertThrows(
                BusinessException.class,
                () -> creditService.chargeGenerationTask("task-1")
        );

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), failure.getCode());
        verify(persistenceService).loadTaskProviderCostObservation("task-1");
        verify(persistenceService, never()).lockActiveAccount(any());
        verify(persistenceService, never()).updateBalance(anyLong(), anyLong());
        verify(persistenceService, never()).appendTransaction(any());
        verify(persistenceService, never()).settleGenerationTask(anyLong(), anyLong(), anyLong());
        verifyNoInteractions(costCalculator);
    }

    @Test
    void persistedReservationSettlementMustRecoverTaskMarkerWithoutChangingBalanceAgain() {
        when(persistenceService.lockGenerationTask("task-1"))
                .thenReturn(generationTask(false));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_SETTLEMENT, "task-1"))
                .thenReturn(new CreditTransaction(
                        7L, 100L, 2L, 9L, UserCreditTransactionType.GENERATION_SETTLEMENT,
                        "task-1", "settled", null, 100_000L
                ));
        when(persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION, "task-1"))
                .thenReturn(new CreditTransaction(
                        7L, 100L, -3L, 7L, UserCreditTransactionType.GENERATION_RESERVATION,
                        "task-1", "reservation:policy-v1", null, null
                ));

        creditService.chargeGenerationTask("task-1");

        verify(persistenceService).settleGenerationTask(101L, 1L, 100_000L);
        verify(persistenceService, never()).lockActiveAccount(any());
        verify(persistenceService, never()).appendTransaction(any());
        verifyNoInteractions(costCalculator);
    }

    @Test
    void zeroCostGenerationMustStillWriteLedgerForAuditAndIdempotency() {
        when(persistenceService.lockGenerationTask("task-1"))
                .thenReturn(generationTask(false));
        when(costCalculator.calculate(0L)).thenReturn(0L);
        when(persistenceService.loadTaskProviderCostObservation("task-1"))
                .thenReturn(ProviderCostObservation.none());
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 5L));

        creditService.chargeGenerationTask("task-1");

        verify(persistenceService, never()).updateBalance(anyLong(), anyLong());
        ArgumentCaptor<NewCreditTransaction> captor =
                ArgumentCaptor.forClass(NewCreditTransaction.class);
        verify(persistenceService).appendTransaction(captor.capture());
        assertEquals(0L, captor.getValue().changeAmount());
        assertEquals(5L, captor.getValue().balanceAfter());
        assertEquals(0L, captor.getValue().tokenCount());
        assertEquals(100L, captor.getValue().tenantId());
        verify(persistenceService).settleGenerationTask(101L, 0L, 0L);
    }

    @Test
    void existingGenerationLedgerMustRecoverMissingTaskSettlementWithoutChargingAgain() {
        when(persistenceService.lockGenerationTask("task-1"))
                .thenReturn(generationTask(false));
        when(persistenceService.findTransaction(UserCreditTransactionType.GENERATION_CHARGE, "task-1"))
                .thenReturn(new CreditTransaction(
                        7L, 100L, -2L, 3L, UserCreditTransactionType.GENERATION_CHARGE,
                        "task-1", "settled", null, 100_001L
                ));

        creditService.chargeGenerationTask("task-1");

        verify(persistenceService).settleGenerationTask(101L, 2L, 100_001L);
        verify(persistenceService, never()).lockActiveAccount(any());
        verify(persistenceService, never()).appendTransaction(any());
        verifyNoInteractions(costCalculator);
    }

    @Test
    void generationSettlementMustRejectBlankMissingTaskAndMissingAccount() {
        BusinessException blank = assertThrows(
                BusinessException.class,
                () -> creditService.chargeGenerationTask(" ")
        );
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), blank.getCode());

        BusinessException missingTask = assertThrows(
                BusinessException.class,
                () -> creditService.chargeGenerationTask("task-404")
        );
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), missingTask.getCode());

        when(persistenceService.lockGenerationTask("task-1"))
                .thenReturn(generationTask(false));
        when(persistenceService.loadTaskProviderCostObservation("task-1"))
                .thenReturn(new ProviderCostObservation(1L, 0L, 0L, 0L, 0L));
        when(costCalculator.calculate(1L)).thenReturn(1L);
        BusinessException missingAccount = assertThrows(
                BusinessException.class,
                () -> creditService.chargeGenerationTask("task-1")
        );
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), missingAccount.getCode());
    }

    private AdminCreditAdjustmentCommand adjustment(long amount, String remark) {
        return new AdminCreditAdjustmentCommand(REQUEST_ID, 7L, amount, remark, 9L);
    }

    private GenerationCreditReservationCommand reservationCommand(long amount, String pricingReference) {
        return new GenerationCreditReservationCommand("task-1", 7L, 100L, amount, pricingReference);
    }

    private GenerationCreditTask generationTask(boolean settled) {
        return new GenerationCreditTask(101L, "task-1", 7L, 100L, settled);
    }
}
