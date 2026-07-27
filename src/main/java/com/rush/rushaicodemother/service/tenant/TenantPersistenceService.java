package com.rush.rushaicodemother.service.tenant;

import com.rush.rushaicodemother.model.entity.Tenant;
import com.rush.rushaicodemother.model.entity.TenantMembership;

/**
 * 租户持久化服务契约。
 */
public interface TenantPersistenceService {

    Tenant ensurePersonalTenant(Long userId, String displayName);

    Tenant findActiveById(Long tenantId);

    TenantMembership findActiveMembership(Long tenantId, Long userId);
}
