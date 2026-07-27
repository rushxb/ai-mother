package com.rush.rushaicodemother.infrastructure.persistence.release;

import com.rush.rushaicodemother.mapper.AiReleaseCoordinationMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisAiReleaseCoordinationLockTest {

    private final AiReleaseCoordinationMapper mapper = mock(AiReleaseCoordinationMapper.class);
    private final MyBatisAiReleaseCoordinationLock lock =
            new MyBatisAiReleaseCoordinationLock(mapper);

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void lockMustRejectMissingWriteTransaction() {
        assertThrows(IllegalStateException.class, lock::acquire);
        verify(mapper, never()).lockByName("global");
    }

    @Test
    void lockMustRejectReadOnlyTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertThrows(IllegalStateException.class, lock::acquire);
        verify(mapper, never()).lockByName("global");
    }

    @Test
    void lockMustRequireThePreseededGlobalRow() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(mapper.lockByName("global")).thenReturn(null);

        assertThrows(IllegalStateException.class, lock::acquire);
    }

    @Test
    void lockMustAcquireThePreseededGlobalRowInWriteTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(mapper.lockByName("global")).thenReturn("global");

        assertDoesNotThrow(lock::acquire);
        verify(mapper).lockByName("global");
    }
}
