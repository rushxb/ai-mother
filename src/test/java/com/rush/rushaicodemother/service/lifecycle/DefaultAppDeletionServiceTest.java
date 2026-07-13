package com.rush.rushaicodemother.service.lifecycle;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.AppLifecycleDataMapper;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import com.rush.rushaicodemother.service.artifact.AppArtifactDeletionTransaction;
import com.rush.rushaicodemother.service.artifact.AppArtifactLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DefaultAppDeletionServiceTest {

    private AppLifecycleDataMapper lifecycleDataMapper;
    private AppArtifactLifecycleService artifactLifecycleService;
    private DevServerManager devServerManager;
    private AppArtifactDeletionTransaction artifactTransaction;
    private TransactionOperations transactionOperations;
    private DefaultAppDeletionService deletionService;

    @BeforeEach
    void setUp() {
        lifecycleDataMapper = mock(AppLifecycleDataMapper.class);
        artifactLifecycleService = mock(AppArtifactLifecycleService.class);
        devServerManager = mock(DevServerManager.class);
        artifactTransaction = mock(AppArtifactDeletionTransaction.class);
        transactionOperations = immediateTransactions();
        when(artifactLifecycleService.prepareDeletion(org.mockito.ArgumentMatchers.any(App.class)))
                .thenReturn(artifactTransaction);
        when(lifecycleDataMapper.selectDeletionState(11L)).thenReturn(app());
        when(lifecycleDataMapper.hardDeleteApp(11L)).thenReturn(1);
        rebuildService();
    }

    @Test
    void shouldDeleteAllRelationalDataAndCommitArtifactDeletionInOrder() {
        App app = app();

        deletionService.delete(11L);

        InOrder ordered = inOrder(
                devServerManager,
                artifactLifecycleService,
                artifactTransaction,
                lifecycleDataMapper
        );
        ordered.verify(lifecycleDataMapper).selectDeletionState(11L);
        ordered.verify(devServerManager).stopDevServer(11L);
        ordered.verify(artifactLifecycleService).prepareDeletion(app);
        ordered.verify(artifactTransaction).activate();
        ordered.verify(lifecycleDataMapper).deleteGenerationModelCalls(11L);
        ordered.verify(lifecycleDataMapper).deleteGenerationBuildLogs(11L);
        ordered.verify(lifecycleDataMapper).deleteGenerationTasks(11L);
        ordered.verify(lifecycleDataMapper).deleteChatHistory(11L);
        ordered.verify(lifecycleDataMapper).deleteCapabilities(11L);
        ordered.verify(lifecycleDataMapper).deleteDatabaseResources(11L);
        ordered.verify(lifecycleDataMapper).deleteGitRepositories(11L);
        ordered.verify(lifecycleDataMapper).deleteRuntimeChannels(11L);
        ordered.verify(lifecycleDataMapper).deleteAnalyticsConfigurations(11L);
        ordered.verify(lifecycleDataMapper).hardDeleteApp(11L);
        ordered.verify(artifactTransaction).commit();
        verify(artifactTransaction, never()).rollback();
    }

    @Test
    void shouldRejectDeletionWhenApplicationDisappearedBeforeLockAcquisition() {
        when(lifecycleDataMapper.selectDeletionState(11L)).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> deletionService.delete(11L)
        );

        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), exception.getCode());
        verify(lifecycleDataMapper).selectDeletionState(11L);
        verify(devServerManager, never()).stopDevServer(11L);
        verify(artifactLifecycleService, never())
                .prepareDeletion(org.mockito.ArgumentMatchers.any(App.class));
        verify(lifecycleDataMapper, never()).hardDeleteApp(11L);
    }

    @Test
    void shouldRollbackArtifactsWhenApplicationRowWasNotDeleted() {
        when(lifecycleDataMapper.hardDeleteApp(11L)).thenReturn(0);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> deletionService.delete(11L)
        );

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
        verify(artifactTransaction).rollback();
        verify(artifactTransaction, never()).commit();
    }

    @Test
    void shouldRollbackArtifactsWhenRelationalCleanupFails() {
        BusinessException databaseFailure = new BusinessException(ErrorCode.SYSTEM_ERROR, "database failed");
        when(lifecycleDataMapper.deleteGenerationTasks(11L)).thenThrow(databaseFailure);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> deletionService.delete(11L)
        );

        assertSame(databaseFailure, exception);
        verify(artifactTransaction).rollback();
        verify(lifecycleDataMapper, never()).hardDeleteApp(11L);
    }

    @Test
    void shouldRollbackArtifactsWhenTransactionCommitFails() {
        BusinessException commitFailure = new BusinessException(ErrorCode.SYSTEM_ERROR, "commit failed");
        transactionOperations = transactionsFailingAfterCallback(commitFailure);
        rebuildService();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> deletionService.delete(11L)
        );

        assertSame(commitFailure, exception);
        verify(lifecycleDataMapper).hardDeleteApp(11L);
        verify(artifactTransaction).rollback();
    }

    @Test
    void shouldPreserveDatabaseAndRollbackFailures() {
        BusinessException databaseFailure = new BusinessException(ErrorCode.SYSTEM_ERROR, "database failed");
        BusinessException rollbackFailure = new BusinessException(ErrorCode.SYSTEM_ERROR, "rollback failed");
        when(lifecycleDataMapper.deleteGenerationTasks(11L)).thenThrow(databaseFailure);
        doThrow(rollbackFailure).when(artifactTransaction).rollback();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> deletionService.delete(11L)
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        assertSame(databaseFailure, exception.getCause());
        assertEquals(1, exception.getSuppressed().length);
        assertSame(rollbackFailure, exception.getSuppressed()[0]);
    }

    @Test
    void shouldReportArtifactCleanupFailureAfterDatabaseDeletion() {
        BusinessException cleanupFailure = new BusinessException(ErrorCode.SYSTEM_ERROR, "cleanup failed");
        doThrow(cleanupFailure).when(artifactTransaction).commit();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> deletionService.delete(11L)
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        assertSame(cleanupFailure, exception.getCause());
    }

    @Test
    void shouldNotTouchArtifactsOrDatabaseWhenDevServerStopFails() {
        BusinessException stopFailure = new BusinessException(ErrorCode.SYSTEM_ERROR, "stop failed");
        doThrow(stopFailure).when(devServerManager).stopDevServer(11L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> deletionService.delete(11L)
        );

        assertSame(stopFailure, exception);
        verifyNoInteractions(artifactLifecycleService);
        verify(lifecycleDataMapper).selectDeletionState(11L);
        verifyNoMoreInteractions(lifecycleDataMapper);
    }

    private void rebuildService() {
        deletionService = new DefaultAppDeletionService(
                lifecycleDataMapper,
                artifactLifecycleService,
                devServerManager,
                new AppOperationLockManager(),
                transactionOperations
        );
    }

    private TransactionOperations immediateTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(mock(TransactionStatus.class));
            }
        };
    }

    private TransactionOperations transactionsFailingAfterCallback(RuntimeException failure) {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                action.doInTransaction(mock(TransactionStatus.class));
                throw failure;
            }
        };
    }

    private App app() {
        return App.builder()
                .id(11L)
                .userId(7L)
                .codeGenType("html")
                .deployKey("Deploy11")
                .build();
    }
}
