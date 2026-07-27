package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.Tenant;
import com.rush.rushaicodemother.model.entity.TenantMembership;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 租户数据访问映射器。
 */
public interface TenantMapper {

    @Insert("""
            INSERT INTO tenant (
                tenantKey, tenantType, displayName, ownerUserId, status,
                createTime, updateTime, isDelete
            ) VALUES (
                #{tenantKey}, #{tenantType}, #{displayName}, #{ownerUserId}, #{status},
                CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 0
            )
            ON DUPLICATE KEY UPDATE
                id = LAST_INSERT_ID(id),
                updateTime = CURRENT_TIMESTAMP(6)
            """)
    int upsertPersonalTenant(Tenant tenant);

    @Select("""
            SELECT id, tenantKey, tenantType, displayName, ownerUserId, status,
                   createTime, updateTime, isDelete
            FROM tenant
            WHERE tenantKey = #{tenantKey}
              AND tenantType = 'personal'
              AND status = 'active'
              AND isDelete = 0
            LIMIT 1
            """)
    Tenant selectActivePersonalByKey(@Param("tenantKey") String tenantKey);

    @Select("""
            SELECT id, tenantKey, tenantType, displayName, ownerUserId, status,
                   createTime, updateTime, isDelete
            FROM tenant
            WHERE id = #{tenantId}
              AND status = 'active'
              AND isDelete = 0
            LIMIT 1
            """)
    Tenant selectActiveById(@Param("tenantId") Long tenantId);

    @Insert("""
            INSERT INTO tenant_membership (
                tenantId, userId, role, status, joinedAt,
                createTime, updateTime, isDelete
            ) VALUES (
                #{tenantId}, #{userId}, #{role}, #{status}, #{joinedAt},
                CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 0
            )
            ON DUPLICATE KEY UPDATE
                id = LAST_INSERT_ID(id),
                updateTime = CURRENT_TIMESTAMP(6)
            """)
    int upsertMembership(TenantMembership membership);

    @Select("""
            SELECT id, tenantId, userId, role, status, joinedAt,
                   createTime, updateTime, isDelete
            FROM tenant_membership
            WHERE tenantId = #{tenantId}
              AND userId = #{userId}
              AND status = 'active'
              AND isDelete = 0
            LIMIT 1
            """)
    TenantMembership selectActiveMembership(@Param("tenantId") Long tenantId,
                                             @Param("userId") Long userId);
}
