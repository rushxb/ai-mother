package com.rush.rushaicodemother.service.tenant;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.TenantMapper;
import com.rush.rushaicodemother.model.entity.Tenant;
import com.rush.rushaicodemother.model.entity.TenantMembership;
import com.rush.rushaicodemother.model.enums.TenantMembershipStatus;
import com.rush.rushaicodemother.model.enums.TenantRole;
import com.rush.rushaicodemother.model.enums.TenantType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 租户持久化服务实现。
 */
@Service
@RequiredArgsConstructor
public class DefaultTenantPersistenceService implements TenantPersistenceService {

    private static final int MAX_DISPLAY_NAME_LENGTH = 128;

    private final TenantMapper tenantMapper;

    /**
 * 确保{@code Personal}租户已达到可用状态。
 *
 * @param userId 用户编号
 * @param displayName {@code displayName} 对应的调用参数
 * @return {@code Personal}租户
 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Tenant ensurePersonalTenant(Long userId, String displayName) {
        requirePositive(userId, "userId");
        String tenantKey = personalTenantKey(userId);
        Tenant candidate = Tenant.builder()
                .tenantKey(tenantKey)
                .tenantType(TenantType.PERSONAL.getValue())
                .displayName(normalizeDisplayName(displayName, userId))
                .ownerUserId(userId)
                .status("active")
                .build();
        tenantMapper.upsertPersonalTenant(candidate);
        Tenant tenant = tenantMapper.selectActivePersonalByKey(tenantKey);
        if (tenant == null
                || tenant.getId() == null
                || tenant.getId() <= 0
                || !userId.equals(tenant.getOwnerUserId())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "创建个人工作区失败");
        }
        TenantMembership membership = TenantMembership.builder()
                .tenantId(tenant.getId())
                .userId(userId)
                .role(TenantRole.OWNER.getValue())
                .status(TenantMembershipStatus.ACTIVE.getValue())
                .joinedAt(LocalDateTime.now())
                .build();
        tenantMapper.upsertMembership(membership);
        TenantMembership persistedMembership =
                tenantMapper.selectActiveMembership(tenant.getId(), userId);
        if (persistedMembership == null
                || !TenantRole.OWNER.getValue().equalsIgnoreCase(persistedMembership.getRole())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "创建个人工作区成员关系失败");
        }
        return tenant;
    }

    /**
 * 查找匹配的活动按编号。
 *
 * @param tenantId 租户编号
 * @return 活动按编号
 */
    @Override
    public Tenant findActiveById(Long tenantId) {
        requirePositive(tenantId, "tenantId");
        return tenantMapper.selectActiveById(tenantId);
    }

    /**
 * 查找匹配的活动成员关系。
 *
 * @param tenantId 租户编号
 * @param userId 用户编号
 * @return 活动成员关系
 */
    @Override
    public TenantMembership findActiveMembership(Long tenantId, Long userId) {
        requirePositive(tenantId, "tenantId");
        requirePositive(userId, "userId");
        return tenantMapper.selectActiveMembership(tenantId, userId);
    }

    static String personalTenantKey(Long userId) {
        requirePositive(userId, "userId");
        return "personal:" + userId;
    }

    private String normalizeDisplayName(String displayName, Long userId) {
        String normalized = StrUtil.trim(displayName);
        if (StrUtil.isBlank(normalized)) {
            normalized = "个人工作区 " + userId;
        }
        return StrUtil.subPre(normalized, MAX_DISPLAY_NAME_LENGTH);
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
