package com.rush.rushaicodemother.service.lifecycle;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.mapper.AppLifecycleDataMapper;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import com.rush.rushaicodemother.service.artifact.AppArtifactDeletionTransaction;
import com.rush.rushaicodemother.service.artifact.AppArtifactLifecycleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** 默认应用删除生命周期实现。 */
@Service
public class DefaultAppDeletionService implements AppDeletionService {

    private final AppLifecycleDataMapper lifecycleDataMapper;
    private final AppArtifactLifecycleService artifactLifecycleService;
    private final DevServerManager devServerManager;
    private final AppOperationLockManager operationLockManager;
    private final TransactionOperations transactionOperations;

    @Autowired
    public DefaultAppDeletionService(AppLifecycleDataMapper lifecycleDataMapper,
                                     AppArtifactLifecycleService artifactLifecycleService,
                                     DevServerManager devServerManager,
                                     AppOperationLockManager operationLockManager,
                                     PlatformTransactionManager transactionManager) {
        this(
                lifecycleDataMapper,
                artifactLifecycleService,
                devServerManager,
                operationLockManager,
                new TransactionTemplate(transactionManager)
        );
    }

    DefaultAppDeletionService(AppLifecycleDataMapper lifecycleDataMapper,
                              AppArtifactLifecycleService artifactLifecycleService,
                              DevServerManager devServerManager,
                              AppOperationLockManager operationLockManager,
                              TransactionOperations transactionOperations) {
        this.lifecycleDataMapper = lifecycleDataMapper;
        this.artifactLifecycleService = artifactLifecycleService;
        this.devServerManager = devServerManager;
        this.operationLockManager = operationLockManager;
        this.transactionOperations = transactionOperations;
    }

    @Override
    public void delete(Long appId) {
        validateAppId(appId);
        operationLockManager.execute(appId, () -> {
            App currentApp = lifecycleDataMapper.selectDeletionState(appId);
            ThrowUtils.throwIf(currentApp == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
            validateDeletionState(currentApp);
            deleteLocked(currentApp);
        });
    }

    private void deleteLocked(App app) {
        Long appId = app.getId();
        devServerManager.stopDevServer(appId);

        AppArtifactDeletionTransaction artifactTransaction =
                artifactLifecycleService.prepareDeletion(app);
        artifactTransaction.activate();
        try {
            transactionOperations.executeWithoutResult(status -> deleteRelationalData(appId));
        } catch (RuntimeException deletionFailure) {
            rollbackArtifacts(artifactTransaction, deletionFailure);
            throw deletionFailure;
        }

        try {
            artifactTransaction.commit();
        } catch (RuntimeException cleanupFailure) {
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "应用数据已删除，但隔离产物清理失败，请联系管理员处理",
                    cleanupFailure
            );
        }
    }

    private void deleteRelationalData(Long appId) {
        lifecycleDataMapper.deleteGenerationModelCalls(appId);
        lifecycleDataMapper.deleteGenerationBuildLogs(appId);
        lifecycleDataMapper.deleteGenerationTaskSpans(appId);
        lifecycleDataMapper.deleteGenerationTasks(appId);
        lifecycleDataMapper.deleteChatHistory(appId);
        lifecycleDataMapper.deleteCapabilities(appId);
        lifecycleDataMapper.deleteDatabaseResources(appId);
        lifecycleDataMapper.deleteGitRepositories(appId);
        lifecycleDataMapper.deleteRuntimeChannels(appId);
        lifecycleDataMapper.deleteAnalyticsConfigurations(appId);
        int deletedRows = lifecycleDataMapper.hardDeleteApp(appId);
        ThrowUtils.throwIf(deletedRows != 1, ErrorCode.OPERATION_ERROR, "删除应用失败");
    }

    private void rollbackArtifacts(AppArtifactDeletionTransaction artifactTransaction,
                                   RuntimeException deletionFailure) {
        try {
            artifactTransaction.rollback();
        } catch (RuntimeException rollbackFailure) {
            BusinessException consistencyFailure = new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "删除应用失败且本地产物恢复失败，请联系管理员处理",
                    deletionFailure
            );
            consistencyFailure.addSuppressed(rollbackFailure);
            throw consistencyFailure;
        }
    }

    private void validateAppId(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0,
                ErrorCode.PARAMS_ERROR, "应用 ID 错误");
    }

    private void validateDeletionState(App app) {
        ThrowUtils.throwIf(app.getId() == null || app.getId() <= 0,
                ErrorCode.SYSTEM_ERROR, "应用 ID 数据异常");
        ThrowUtils.throwIf(app.getUserId() == null || app.getUserId() <= 0,
                ErrorCode.SYSTEM_ERROR, "应用所有者数据异常");
    }
}
