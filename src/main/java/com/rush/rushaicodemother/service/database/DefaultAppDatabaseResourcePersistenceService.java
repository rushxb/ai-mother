package com.rush.rushaicodemother.service.database;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.mapper.AppDatabaseResourceMapper;
import com.rush.rushaicodemother.model.entity.AppDatabaseResource;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** MyBatis 应用 Database 资源持久化实现。 */
@Service
@RequiredArgsConstructor
public class DefaultAppDatabaseResourcePersistenceService
        implements AppDatabaseResourcePersistenceService {

    private static final String STATUS_ACTIVE = "active";

    private final AppDatabaseResourceMapper appDatabaseResourceMapper;

    /**
 * 启用资源。
 *
 * @param resource 资源
 * @return 资源
 */
    @Override
    @Transactional
    public AppDatabaseResource enableResource(NewAppDatabaseResource resource) {
        validateNewResource(resource);
        AppDatabaseResource persistenceModel = AppDatabaseResource.builder()
                .appId(resource.appId())
                .userId(resource.userId())
                .resourceId(resource.resourceId().trim())
                .resourceName(resource.resourceName().trim())
                .databaseUrl(resource.databaseUrl().trim())
                .dbEngine(resource.dbEngine().trim())
                .backendRuntime(resource.backendRuntime().trim())
                .sqlExecutionPolicy(resource.sqlExecutionPolicy().trim())
                .status(STATUS_ACTIVE)
                .lastUsedTime(resource.lastUsedTime())
                .build();
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            appDatabaseResourceMapper.upsertActiveResource(persistenceModel);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Database 资源标识冲突，请联系管理员检查资源数据",
                    exception
            );
        }

        AppDatabaseResource activeResource = appDatabaseResourceMapper.selectActiveByAppId(resource.appId());
        if (activeResource == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "Database 资源启用失败，请稍后重试");
        }
        return activeResource;
    }

    /**
 * 查找匹配的活动按应用编号。
 *
 * @param appId 应用编号
 * @return 活动按应用编号
 */
    @Override
    public AppDatabaseResource findActiveByAppId(Long appId) {
        validateAppId(appId);
        return appDatabaseResourceMapper.selectActiveByAppId(appId);
    }

    /**
 * 查找匹配的活动按应用{@code Ids}。
 *
 * @param appIds 待处理的 {@code appIds} 集合
 * @return 活动按应用{@code Ids}集合
 */
    @Override
    public List<AppDatabaseResource> findActiveByAppIds(Collection<Long> appIds) {
        if (appIds == null || appIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> validAppIds = appIds.stream()
                .filter(Objects::nonNull)
                .filter(appId -> appId > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (validAppIds.isEmpty()) {
            return List.of();
        }
        List<AppDatabaseResource> resources = appDatabaseResourceMapper.selectActiveByAppIds(validAppIds);
        return resources == null
                ? List.of()
                : resources.stream().filter(Objects::nonNull).toList();
    }

    /** 校验{@code ate}{@code New}资源是否有效。 */
    private void validateNewResource(NewAppDatabaseResource resource) {
        ThrowUtils.throwIf(resource == null, ErrorCode.PARAMS_ERROR, "Database 资源参数不能为空");
        validateAppId(resource.appId());
        ThrowUtils.throwIf(resource.userId() == null || resource.userId() <= 0,
                ErrorCode.PARAMS_ERROR, "应用所属用户 ID 不合法");
        ThrowUtils.throwIf(StrUtil.isBlank(resource.resourceId()),
                ErrorCode.PARAMS_ERROR, "Database 资源标识不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(resource.resourceName()),
                ErrorCode.PARAMS_ERROR, "Database 资源名称不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(resource.databaseUrl()),
                ErrorCode.PARAMS_ERROR, "Database 访问地址不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(resource.dbEngine()),
                ErrorCode.PARAMS_ERROR, "Database 引擎不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(resource.backendRuntime()),
                ErrorCode.PARAMS_ERROR, "Database 后端运行时不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(resource.sqlExecutionPolicy()),
                ErrorCode.PARAMS_ERROR, "SQL 执行策略不能为空");
        ThrowUtils.throwIf(resource.lastUsedTime() == null,
                ErrorCode.PARAMS_ERROR, "Database 最后使用时间不能为空");
    }

    private void validateAppId(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不合法");
    }
}
