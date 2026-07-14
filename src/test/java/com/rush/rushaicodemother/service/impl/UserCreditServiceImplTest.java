package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.UserCreditTransactionType;
import com.rush.rushaicodemother.service.credit.AdminCreditAdjustmentCommand;
import com.rush.rushaicodemother.service.credit.UserCreditCostCalculator;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.CreditAccount;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.CreditTransaction;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.GenerationCreditTask;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.NewCreditTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserCreditServiceImplTest {

    private static final String REQUEST_ID = "550e8400-e29b-41d4-a716-446655440000";

    private UserCreditPersistenceService persistenceService;
    private UserCreditCostCalculator costCalculator;
    private UserCreditServiceImpl creditService;

    @BeforeEach
    void setUp() {
        persistenceService = mock(UserCreditPersistenceService.class);
        costCalculator = mock(UserCreditCostCalculator.class);
        creditService = new UserCreditServiceImpl(persistenceService, costCalculator);
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
                .thenReturn(new GenerationCreditTask(101L, "task-1", 7L, true));

        creditService.chargeGenerationTask("task-1");

        verify(persistenceService).lockGenerationTask("task-1");
        verify(persistenceService, never()).findTransaction(any(), any());
        verifyNoInteractions(costCalculator);
    }

    @Test
    void generationChargeMustCapCostAtBalanceAndPersistOneAuditableSettlement() {
        when(persistenceService.lockGenerationTask("task-1"))
                .thenReturn(new GenerationCreditTask(101L, "task-1", 7L, false));
        when(persistenceService.sumPositiveTaskTokens("task-1")).thenReturn(200_001L);
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
        verify(persistenceService).settleGenerationTask(101L, 2L, 200_001L);
    }

    @Test
    void zeroCostGenerationMustStillWriteLedgerForAuditAndIdempotency() {
        when(persistenceService.lockGenerationTask("task-1"))
                .thenReturn(new GenerationCreditTask(101L, "task-1", 7L, false));
        when(costCalculator.calculate(0L)).thenReturn(0L);
        when(persistenceService.lockActiveAccount(7L)).thenReturn(new CreditAccount(7L, 5L));

        creditService.chargeGenerationTask("task-1");

        verify(persistenceService, never()).updateBalance(anyLong(), anyLong());
        ArgumentCaptor<NewCreditTransaction> captor =
                ArgumentCaptor.forClass(NewCreditTransaction.class);
        verify(persistenceService).appendTransaction(captor.capture());
        assertEquals(0L, captor.getValue().changeAmount());
        assertEquals(5L, captor.getValue().balanceAfter());
        assertEquals(0L, captor.getValue().tokenCount());
        verify(persistenceService).settleGenerationTask(101L, 0L, 0L);
    }

    @Test
    void existingGenerationLedgerMustRecoverMissingTaskSettlementWithoutChargingAgain() {
        when(persistenceService.lockGenerationTask("task-1"))
                .thenReturn(new GenerationCreditTask(101L, "task-1", 7L, false));
        when(persistenceService.findTransaction(UserCreditTransactionType.GENERATION_CHARGE, "task-1"))
                .thenReturn(new CreditTransaction(
                        7L, -2L, 3L, UserCreditTransactionType.GENERATION_CHARGE,
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
                .thenReturn(new GenerationCreditTask(101L, "task-1", 7L, false));
        when(persistenceService.sumPositiveTaskTokens("task-1")).thenReturn(1L);
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
}
