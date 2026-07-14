package com.rush.rushaicodemother.mapper;

import com.mybatisflex.core.BaseMapper;
import com.rush.rushaicodemother.model.entity.AppDatabaseResource;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/** 应用 Database 资源映射层。 */
public interface AppDatabaseResourceMapper extends BaseMapper<AppDatabaseResource> {

    /**
     * 原子创建或恢复指定应用的 Database 资源。
     *
     * <p>具体字段白名单由 Mapper XML 固定，禁止退回通用实体插入或更新。</p>
     */
    int upsertActiveResource(AppDatabaseResource resource);

    /** 查询指定应用当前启用且未删除的 Database 资源。 */
    AppDatabaseResource selectActiveByAppId(@Param("appId") Long appId);

    /** 批量查询应用当前启用且未删除的 Database 资源。 */
    List<AppDatabaseResource> selectActiveByAppIds(@Param("appIds") Collection<Long> appIds);
}
