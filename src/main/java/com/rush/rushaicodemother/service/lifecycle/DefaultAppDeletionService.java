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
    private final AppMemoryLifecycleService memoryLifecycleService;
    private final TransactionOperations transactionOperations;

    /**
 * 创建默认应用删除服务实例并完成必要的依赖和初始状态设置。
 *
 * @param lifecycleDataMapper {@code lifecycleDataMapper} 对应的调用参数
 * @param artifactLifecycleService 制品生命周期服务
 * @param devServerManager 开发服务器管理器
 * @param operationLockManager 操作锁管理器
 * @param memoryLifecycleService 记忆生命周期服务
 * @param transactionManager 事务管理器
 */
    @Autowired
    public DefaultAppDeletionService(AppLifecycleDataMapper lifecycleDataMapper,
                                     AppArtifactLifecycleService artifactLifecycleService,
                                     DevServerManager devServerManager,
                                     AppOperationLockManager operationLockManager,
                                     AppMemoryLifecycleService memoryLifecycleService,
                                     PlatformTransactionManager transactionManager) {
        this(
                lifecycleDataMapper,
                artifactLifecycleService,
                devServerManager,
                operationLockManager,
                memoryLifecycleService,
                new TransactionTemplate(transactionManager)
        );
    }

    DefaultAppDeletionService(AppLifecycleDataMapper lifecycleDataMapper,
                              AppArtifactLifecycleService artifactLifecycleService,
                              DevServerManager devServerManager,
                              AppOperationLockManager operationLockManager,
                              AppMemoryLifecycleService memoryLifecycleService,
                              TransactionOperations transactionOperations) {
        this.lifecycleDataMapper = lifecycleDataMapper;
        this.artifactLifecycleService = artifactLifecycleService;
        this.devServerManager = devServerManager;
        this.operationLockManager = operationLockManager;
        this.memoryLifecycleService = memoryLifecycleService;
        this.transactionOperations = transactionOperations;
    }

    /**
 * 删除默认应用删除。
 *
 * @param appId 应用编号
 */
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

    /** 删除{@code Locked}。 */
    private void deleteLocked(App app) {
        Long appId = app.getId();
        devServerManager.stopDevServer(appId);

        AppArtifactDeletionTransaction artifactTransaction =
                artifactLifecycleService.prepareDeletion(app);
        artifactTransaction.activate();
        try {
            transactionOperations.executeWithoutResult(status -> {
                memoryLifecycleService.scheduleApplicationMemoryDeletion(
                        app.getTenantId(), appId, app.getUserId());
                deleteRelationalData(appId);
            });
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

    /** 删除{@code Relational}{@code Data}。 */
    private void deleteRelationalData(Long appId) {
        lifecycleDataMapper.deleteGenerationModelCalls(appId);
        lifecycleDataMapper.deleteGenerationBuildLogs(appId);
        lifecycleDataMapper.deleteGenerationTaskSpans(appId);
        lifecycleDataMapper.deleteGenerationToolApprovals(appId);
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

    /** 处理回滚{@code Artifacts}。 */
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
        ThrowUtils.throwIf(app.getTenantId() == null || app.getTenantId() <= 0,
                ErrorCode.SYSTEM_ERROR, "application tenant data is invalid");
        ThrowUtils.throwIf(app.getId() == null || app.getId() <= 0,
                ErrorCode.SYSTEM_ERROR, "应用 ID 数据异常");
        ThrowUtils.throwIf(app.getUserId() == null || app.getUserId() <= 0,
                ErrorCode.SYSTEM_ERROR, "应用所有者数据异常");
    }
}
