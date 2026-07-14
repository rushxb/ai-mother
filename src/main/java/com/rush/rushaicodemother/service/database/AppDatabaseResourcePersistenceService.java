package com.rush.rushaicodemother.service.database;

import com.rush.rushaicodemother.model.entity.AppDatabaseResource;

import java.util.Collection;
import java.util.List;

/**
 * 应用 Database 资源持久化边界。
 *
 * <p>只暴露启用和 active 资源查询场景，禁止业务层依赖 MyBatis-Flex 通用 CRUD。</p>
 */
public interface AppDatabaseResourcePersistenceService {

    /** 原子创建、幂等刷新或恢复指定应用的 Database 资源。 */
    AppDatabaseResource enableResource(NewAppDatabaseResource resource);

    /** 查询指定应用当前启用且未删除的 Database 资源。 */
    AppDatabaseResource findActiveByAppId(Long appId);

    /** 批量查询当前启用且未删除的 Database 资源。 */
    List<AppDatabaseResource> findActiveByAppIds(Collection<Long> appIds);
}
