package com.rush.rushaicodemother.service.credit;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.UserCreditMapper;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.entity.UserCreditTransaction;
import com.rush.rushaicodemother.model.enums.UserCreditTransactionType;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.CreditAccount;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.CreditTransaction;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.GenerationCreditTask;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.NewCreditTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultUserCreditPersistenceServiceTest {

    private UserCreditMapper mapper;
    private DefaultUserCreditPersistenceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(UserCreditMapper.class);
        service = new DefaultUserCreditPersistenceService(mapper);
    }

    @Test
    void accountQueriesMustMapOnlyValidatedCreditProjection() {
        when(mapper.selectActiveCreditAccount(7L)).thenReturn(User.builder()
                .id(7L)
                .creditBalance(5L)
                .build());

        CreditAccount account = service.findActiveAccount(7L);

        assertEquals(7L, account.userId());
        assertEquals(5L, account.balance());
        assertNull(service.lockActiveAccount(8L));
    }

    @Test
    void corruptAccountProjectionMustFailAtPersistenceBoundary() {
        when(mapper.selectActiveCreditAccount(7L)).thenReturn(User.builder()
                .id(7L)
                .creditBalance(null)
                .build());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.findActiveAccount(7L)
        );

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
    }

    @Test
    void generationTaskLockMustMapSettlementState() {
        when(mapper.selectGenerationTaskForUpdate("task-1")).thenReturn(GenerationTask.builder()
                .id(101L)
                .taskId("task-1")
                .userId(7L)
                .creditCharged(0)
                .build());

        GenerationCreditTask task = service.lockGenerationTask("task-1");

        assertEquals(101L, task.recordId());
        assertEquals(7L, task.userId());
        assertFalse(task.settled());
    }

    @Test
    void transactionLookupMustMapTypedLedgerRecord() {
        when(mapper.selectTransactionByTypeAndBizId("ADMIN_ADJUST", "request-1"))
                .thenReturn(UserCreditTransaction.builder()
                        .userId(7L)
                        .changeAmount(2L)
                        .balanceAfter(8L)
                        .type("ADMIN_ADJUST")
                        .bizId("request-1")
                        .remark("reason")
                        .adminUserId(9L)
                        .build());

        CreditTransaction transaction = service.findTransaction(
                UserCreditTransactionType.ADMIN_ADJUST,
                "request-1"
        );

        assertEquals(UserCreditTransactionType.ADMIN_ADJUST, transaction.type());
        assertEquals(8L, transaction.balanceAfter());
        assertEquals(9L, transaction.adminUserId());
    }

    @Test
    void tokenSumMustNormalizeNullButRejectNegativeDatabaseResult() {
        assertEquals(0L, service.sumPositiveTaskTokens("task-1"));

        when(mapper.sumPositiveTaskTokens("task-2")).thenReturn(-1L);
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.sumPositiveTaskTokens("task-2")
        );

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
    }

    @Test
    void appendTransactionMustMapControlledFields() {
        when(mapper.insertTransaction(any())).thenReturn(1);
        NewCreditTransaction command = generationTransaction();

        service.appendTransaction(command);

        ArgumentCaptor<UserCreditTransaction> captor =
                ArgumentCaptor.forClass(UserCreditTransaction.class);
        verify(mapper).insertTransaction(captor.capture());
        UserCreditTransaction entity = captor.getValue();
        assertNull(entity.getId());
        assertEquals(7L, entity.getUserId());
        assertEquals(-2L, entity.getChangeAmount());
        assertEquals("GENERATION_CHARGE", entity.getType());
        assertEquals("task-1", entity.getBizId());
        assertEquals(100_001L, entity.getTokenCount());
    }

    @Test
    void appendTransactionMustMapDuplicateBusinessKeyToStableError() {
        when(mapper.insertTransaction(any())).thenThrow(new DuplicateKeyException("uk_type_bizId"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.appendTransaction(generationTransaction())
        );

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
        assertEquals("积分流水业务标识冲突，请勿重复提交", exception.getMessage());
    }

    @Test
    void invalidTransactionMustBeRejectedBeforeMapperInvocation() {
        NewCreditTransaction invalid = new NewCreditTransaction(
                7L, 1L, 4L, UserCreditTransactionType.GENERATION_CHARGE,
                "task-1", "invalid positive charge", null, 1L
        );

        assertThrows(BusinessException.class, () -> service.appendTransaction(invalid));

        verifyNoInteractions(mapper);
    }

    @Test
    void balanceAndSettlementWritesMustRequireExactlyOneAffectedRow() {
        BusinessException balanceFailure = assertThrows(
                BusinessException.class,
                () -> service.updateBalance(7L, 5L)
        );
        BusinessException settlementFailure = assertThrows(
                BusinessException.class,
                () -> service.settleGenerationTask(101L, 2L, 100L)
        );

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), balanceFailure.getCode());
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), settlementFailure.getCode());
        verify(mapper).updateCreditBalance(7L, 5L);
        verify(mapper).updateCreditSettlement(101L, 2L, 100L);
    }

    @Test
    void invalidIdentifiersMustNotReachMapper() {
        assertThrows(BusinessException.class, () -> service.findActiveAccount(0L));
        assertThrows(BusinessException.class, () -> service.lockGenerationTask(" "));
        assertThrows(BusinessException.class, () -> service.findTransaction(null, "biz"));

        verify(mapper, never()).selectActiveCreditAccount(any());
        verify(mapper, never()).selectGenerationTaskForUpdate(any());
        verify(mapper, never()).selectTransactionByTypeAndBizId(any(), any());
    }

    private NewCreditTransaction generationTransaction() {
        return new NewCreditTransaction(
                7L,
                -2L,
                3L,
                UserCreditTransactionType.GENERATION_CHARGE,
                "task-1",
                "AI generation charge",
                null,
                100_001L
        );
    }
}
