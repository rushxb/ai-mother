package com.rush.rushaicodemother.service.tenant;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.entity.Tenant;
import com.rush.rushaicodemother.model.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 租户供给服务实现。
 */
@Service
@RequiredArgsConstructor
public class TenantProvisioningService {

    private final TenantPersistenceService tenantPersistenceService;

    /**
 * 确保{@code Personal}租户已达到可用状态。
 *
 * @param user 用户
 * @return {@code Personal}租户
 */
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

    /**
 * 校验并返回有效的{@code Personal}租户编号。
 *
 * @param user 用户
 * @return 计算或处理后的数值结果
 */
    public Long requirePersonalTenantId(User user) {
        Tenant tenant = ensurePersonalTenant(user);
        if (tenant.getId() == null || tenant.getId() <= 0) {
            throw new IllegalStateException("personal tenant id is unavailable");
        }
        return tenant.getId();
    }
}
