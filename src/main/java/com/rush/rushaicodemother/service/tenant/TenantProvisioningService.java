package com.rush.rushaicodemother.service.tenant;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.entity.Tenant;
import com.rush.rushaicodemother.model.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TenantProvisioningService {

    private final TenantPersistenceService tenantPersistenceService;

    public Tenant ensurePersonalTenant(User user) {
        if (user == null || user.getId() == null || user.getId() <= 0) {
            throw new IllegalArgumentException("user identity is required for tenant provisioning");
        }
        String displayName = StrUtil.blankToDefault(user.getUserName(), user.getUserAccount());
        return tenantPersistenceService.ensurePersonalTenant(user.getId(), displayName);
    }

    public Tenant ensurePersonalTenant(Long userId, String displayName) {
        return tenantPersistenceService.ensurePersonalTenant(userId, displayName);
    }

    public Long requirePersonalTenantId(User user) {
        Tenant tenant = ensurePersonalTenant(user);
        if (tenant.getId() == null || tenant.getId() <= 0) {
            throw new IllegalStateException("personal tenant id is unavailable");
        }
        return tenant.getId();
    }
}
